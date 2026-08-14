package com.example.siteshield

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class DownloadRequestInfo(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long?,
) {
    companion object {
        fun fromWebViewCallback(
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long,
        ): DownloadRequestInfo? {
            val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return DownloadRequestInfo(
                url = normalizedUrl,
                userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() },
                contentDisposition = contentDisposition?.trim()?.takeIf { it.isNotEmpty() },
                mimeType = DownloadMimeResolver.normalize(mimeType),
                contentLength = contentLength.takeIf { it > 0L },
            )
        }
    }
}

class DownloadIntentTracker(
    private val validityMs: Long = 1_500L,
    private val clockMs: () -> Long,
) {
    private val pendingGestureAt = AtomicReference<Long?>(null)

    fun recordGesture() {
        pendingGestureAt.set(clockMs())
    }

    fun consumeIfRecent(): Boolean {
        val recordedAt = pendingGestureAt.getAndSet(null) ?: return false
        val age = clockMs() - recordedAt
        return age in 0..validityMs
    }

    fun clear() {
        pendingGestureAt.set(null)
    }
}

sealed interface DownloadPolicyDecision {
    data class Allow(val host: String) : DownloadPolicyDecision
    data class Block(val reason: DownloadBlockReason, val host: String? = null) : DownloadPolicyDecision
}

enum class DownloadBlockReason {
    MALFORMED_URL,
    CLEAR_TEXT_NOT_ALLOWED,
    UNSUPPORTED_SCHEME,
    UNSUPPORTED_INLINE_DATA,
    HOSTILE_HOST,
}

object DownloadPolicy {
    fun decide(url: String): DownloadPolicyDecision {
        val parsed = runCatching { URI(url) }.getOrNull()
            ?: return DownloadPolicyDecision.Block(DownloadBlockReason.MALFORMED_URL)
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme == "blob" || scheme == "data" || scheme == "filesystem") {
            return DownloadPolicyDecision.Block(DownloadBlockReason.UNSUPPORTED_INLINE_DATA)
        }
        if (scheme == "http") {
            return DownloadPolicyDecision.Block(DownloadBlockReason.CLEAR_TEXT_NOT_ALLOWED, parsed.host)
        }
        if (scheme != "https") {
            return DownloadPolicyDecision.Block(DownloadBlockReason.UNSUPPORTED_SCHEME, parsed.host)
        }
        val host = parsed.host.normalizedHost()
            ?: return DownloadPolicyDecision.Block(DownloadBlockReason.MALFORMED_URL)
        if (
            CommonRules.blockedHosts.any { it.matches(host) } ||
            CommonRules.suspiciousHosts.any { it.matches(host) }
        ) {
            return DownloadPolicyDecision.Block(DownloadBlockReason.HOSTILE_HOST, host)
        }
        return DownloadPolicyDecision.Allow(host)
    }
}

data class PreparedDownload(
    val request: DownloadRequestInfo,
    val filename: String,
    val mimeType: String,
    val dangerousFileType: Boolean,
)

object DownloadPreparation {
    fun prepare(request: DownloadRequestInfo): PreparedDownload {
        val filename = DownloadFilenameResolver.resolve(
            request.contentDisposition,
            request.url,
            request.mimeType,
        )
        val mimeType = DownloadMimeResolver.resolve(request.mimeType, filename)
        return PreparedDownload(
            request = request,
            filename = filename,
            mimeType = mimeType,
            dangerousFileType = DownloadFilenameResolver.isPotentiallyExecutable(filename, mimeType),
        )
    }
}

object DownloadFilenameResolver {
    private val encodedFilename = Regex("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)")
    private val quotedFilename = Regex("(?i)filename\\s*=\\s*\"([^\"]*)\"")
    private val plainFilename = Regex("(?i)filename\\s*=\\s*([^;]+)")
    private val dangerousExtensions = setOf("apk", "exe", "msi", "bat", "cmd", "com", "js", "html", "htm", "jar", "scr")

    fun resolve(contentDisposition: String?, url: String, callbackMimeType: String?): String {
        val dispositionName = contentDisposition?.let(::filenameFromContentDisposition)
        val urlName = runCatching { URI(url).path }
            .getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePercentSafely)
        val mimeFallback = DownloadMimeResolver.extensionForMime(callbackMimeType)
            ?.let { "download.$it" }
        return sanitize(dispositionName ?: urlName ?: mimeFallback ?: "download.bin")
    }

    fun sanitize(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            leaf.forEach { character ->
                when {
                    character.code < 32 || character.code == 127 -> Unit
                    character in setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') -> append('_')
                    else -> append(character)
                }
            }
        }
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .take(160)
            .ifBlank { "download.bin" }
        return if (cleaned == "." || cleaned == "..") "download.bin" else cleaned
    }

    fun isPotentiallyExecutable(filename: String, mimeType: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in dangerousExtensions || mimeType in setOf(
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-executable",
        )
    }

    private fun filenameFromContentDisposition(value: String): String? {
        encodedFilename.find(value)?.groupValues?.getOrNull(1)?.let { encoded ->
            decodePercentSafely(encoded.trim().trim('"')).takeIf { it.isNotBlank() }?.let { return it }
        }
        quotedFilename.find(value)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return plainFilename.find(value)?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private fun decodePercentSafely(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
            .getOrDefault(value)
}

object DownloadMimeResolver {
    private val validMimeType = Regex("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$")
    private val extensionMimeTypes = mapOf(
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "zip" to "application/zip",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "apk" to "application/vnd.android.package-archive",
        "html" to "text/html",
        "htm" to "text/html",
        "js" to "text/javascript",
    )

    fun resolve(callbackMimeType: String?, filename: String): String {
        val callback = normalize(callbackMimeType)?.takeIf { it != "*/*" }
        return callback ?: extensionMimeTypes[filename.substringAfterLast('.', "").lowercase(Locale.US)]
            ?: "application/octet-stream"
    }

    fun normalize(mimeType: String?): String? = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(validMimeType::matches)

    fun extensionForMime(mimeType: String?): String? {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        return extensionMimeTypes.entries.firstOrNull { it.value == normalized }?.key
    }
}

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    UNKNOWN,
}

object DownloadStatusMapper {
    fun map(platformStatus: Int): DownloadState = when (platformStatus) {
        1 -> DownloadState.QUEUED
        2 -> DownloadState.DOWNLOADING
        4 -> DownloadState.PAUSED
        8 -> DownloadState.COMPLETED
        16 -> DownloadState.FAILED
        else -> DownloadState.UNKNOWN
    }

    fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int? {
        if (downloadedBytes < 0L || totalBytes <= 0L) return null
        return ((downloadedBytes.coerceAtMost(totalBytes).toDouble() / totalBytes.toDouble()) * 100.0).toInt()
    }
}

data class DownloadRecord(
    val downloadManagerId: Long,
    val filename: String,
    val mimeType: String,
    val createdAtMs: Long,
    val profileId: String,
)

data class DownloadItem(
    val record: DownloadRecord,
    val state: DownloadState,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
) {
    val progressPercent: Int?
        get() = DownloadStatusMapper.progressPercent(downloadedBytes ?: -1L, totalBytes ?: -1L)
}
