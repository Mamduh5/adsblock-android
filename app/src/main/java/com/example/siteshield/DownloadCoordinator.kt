package com.example.siteshield

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

interface DownloadMetadataRepository {
    fun list(): List<DownloadRecord>
    fun add(record: DownloadRecord)
    fun remove(downloadManagerId: Long)
}

class PreferencesDownloadMetadataRepository(context: Context) : DownloadMetadataRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun list(): List<DownloadRecord> = preferences.getStringSet(KEY_RECORDS, emptySet())
        .orEmpty()
        .mapNotNull(::decode)
        .sortedByDescending { it.createdAtMs }

    @Synchronized
    override fun add(record: DownloadRecord) {
        val records = list().filterNot { it.downloadManagerId == record.downloadManagerId }
            .plus(record)
            .sortedByDescending { it.createdAtMs }
            .take(MAX_RECORDS)
        preferences.edit().putStringSet(KEY_RECORDS, records.map(::encode).toSet()).apply()
    }

    @Synchronized
    override fun remove(downloadManagerId: Long) {
        preferences.edit().putStringSet(
            KEY_RECORDS,
            list().filterNot { it.downloadManagerId == downloadManagerId }.map(::encode).toSet(),
        ).apply()
    }

    private fun encode(record: DownloadRecord): String = listOf(
        record.downloadManagerId.toString(),
        record.createdAtMs.toString(),
        encodeText(record.filename),
        encodeText(record.mimeType),
        encodeText(record.profileId),
    ).joinToString("|")

    private fun decode(value: String): DownloadRecord? {
        val parts = value.split('|')
        if (parts.size != 5) return null
        return runCatching {
            DownloadRecord(
                downloadManagerId = parts[0].toLong(),
                createdAtMs = parts[1].toLong(),
                filename = DownloadFilenameResolver.sanitize(decodeText(parts[2])),
                mimeType = decodeText(parts[3]),
                profileId = decodeText(parts[4]),
            )
        }.getOrNull()
    }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    companion object {
        private const val PREFERENCES_NAME = "site_shield_downloads"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 50
    }
}

sealed interface DownloadEnqueueResult {
    data class Enqueued(val record: DownloadRecord) : DownloadEnqueueResult
    data class Rejected(val reason: DownloadBlockReason) : DownloadEnqueueResult
    data class Failed(val message: String) : DownloadEnqueueResult
}

sealed interface DownloadOpenResult {
    data object Opened : DownloadOpenResult
    data object NotCompleted : DownloadOpenResult
    data object NoHandler : DownloadOpenResult
    data object Missing : DownloadOpenResult
    data class Failed(val message: String) : DownloadOpenResult
}

class DownloadCoordinator(
    context: Context,
    private val repository: DownloadMetadataRepository = PreferencesDownloadMetadataRepository(context),
    private val onEvent: (DebugEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val reportedTerminalStates = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(prepared: PreparedDownload, profileId: String): DownloadEnqueueResult {
        val policy = DownloadPolicy.decide(prepared.request.url)
        if (policy is DownloadPolicyDecision.Block) {
            safeEvent("download-blocked", prepared, profileId, "reason=${policy.reason}")
            return DownloadEnqueueResult.Rejected(policy.reason)
        }

        return runCatching {
            val request = DownloadManager.Request(Uri.parse(prepared.request.url)).apply {
                setTitle(prepared.filename)
                setDescription("Downloaded by Site Shield")
                setMimeType(prepared.mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, prepared.filename)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                prepared.request.userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(prepared.request.url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addRequestHeader("Cookie", it) }
            }
            val id = downloadManager.enqueue(request)
            val record = DownloadRecord(
                downloadManagerId = id,
                filename = prepared.filename,
                mimeType = prepared.mimeType,
                createdAtMs = System.currentTimeMillis(),
                profileId = profileId,
            )
            repository.add(record)
            safeEvent("download-enqueued", prepared, profileId, "id=$id")
            DownloadEnqueueResult.Enqueued(record)
        }.getOrElse { error ->
            onEvent(
                DebugEvent(
                    category = DebugEventCategory.DOWNLOAD,
                    message = "download-failed",
                    detail = "filename=${prepared.filename}, profile=$profileId, reason=${error.javaClass.simpleName}",
                ),
            )
            DownloadEnqueueResult.Failed(error.javaClass.simpleName)
        }
    }

    fun queryDownloads(callback: (List<DownloadItem>) -> Unit) {
        executor.execute {
            val records = repository.list()
            val itemsById = queryStatuses(records)
            val items = records.map { record ->
                itemsById[record.downloadManagerId] ?: DownloadItem(record, DownloadState.UNKNOWN, null, null)
            }
            items.forEach(::reportTerminalStateOnce)
            mainHandler.post { callback(items) }
        }
    }

    fun cancel(downloadManagerId: Long, callback: (Boolean) -> Unit) {
        executor.execute {
            val owned = repository.list().any { it.downloadManagerId == downloadManagerId }
            val removed = owned && runCatching { downloadManager.remove(downloadManagerId) > 0 }.getOrDefault(false)
            if (removed) repository.remove(downloadManagerId)
            mainHandler.post { callback(removed) }
        }
    }

    fun open(downloadManagerId: Long, mimeType: String, callback: (DownloadOpenResult) -> Unit) {
        executor.execute {
            val owned = repository.list().any { it.downloadManagerId == downloadManagerId }
            if (!owned) {
                mainHandler.post { callback(DownloadOpenResult.Missing) }
                return@execute
            }
            val result = runCatching {
                val uri = downloadManager.getUriForDownloadedFile(downloadManagerId)
                    ?: return@runCatching DownloadOpenResult.Missing
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                mainHandler.post {
                    val handler = intent.resolveActivity(appContext.packageManager)
                    if (handler == null) {
                        callback(DownloadOpenResult.NoHandler)
                    } else {
                        runCatching {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            appContext.startActivity(Intent.createChooser(intent, "Open download").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }.onSuccess {
                            callback(DownloadOpenResult.Opened)
                        }.onFailure { error ->
                            callback(DownloadOpenResult.Failed(error.javaClass.simpleName))
                        }
                    }
                }
                null
            }.getOrElse { DownloadOpenResult.Failed(it.javaClass.simpleName) }
            if (result != null) mainHandler.post { callback(result) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun queryStatuses(records: List<DownloadRecord>): Map<Long, DownloadItem> {
        if (records.isEmpty()) return emptyMap()
        val recordsById = records.associateBy { it.downloadManagerId }
        return runCatching {
            downloadManager.query(
                DownloadManager.Query().setFilterById(*recordsById.keys.toLongArray()),
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val downloadedColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                buildMap {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val record = recordsById[id] ?: continue
                        put(
                            id,
                            DownloadItem(
                                record = record,
                                state = DownloadStatusMapper.map(cursor.getInt(statusColumn)),
                                downloadedBytes = cursor.getLong(downloadedColumn).takeIf { it >= 0L },
                                totalBytes = cursor.getLong(totalColumn).takeIf { it > 0L },
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun safeEvent(action: String, prepared: PreparedDownload, profileId: String, extra: String) {
        val host = (DownloadPolicy.decide(prepared.request.url) as? DownloadPolicyDecision.Allow)?.host ?: "unknown"
        onEvent(
            DebugEvent(
                category = DebugEventCategory.DOWNLOAD,
                message = action,
                detail = "host=$host, mime=${prepared.mimeType}, filename=${prepared.filename}, profile=$profileId, $extra",
            ),
        )
    }

    private fun reportTerminalStateOnce(item: DownloadItem) {
        if (item.state !in setOf(DownloadState.COMPLETED, DownloadState.FAILED)) return
        val key = "${item.record.downloadManagerId}:${item.state}"
        if (!reportedTerminalStates.add(key)) return
        onEvent(
            DebugEvent(
                category = DebugEventCategory.DOWNLOAD,
                message = if (item.state == DownloadState.COMPLETED) "download-complete" else "download-failed",
                detail = "id=${item.record.downloadManagerId}, filename=${item.record.filename}, " +
                    "mime=${item.record.mimeType}, profile=${item.record.profileId}",
            ),
        )
    }
}
