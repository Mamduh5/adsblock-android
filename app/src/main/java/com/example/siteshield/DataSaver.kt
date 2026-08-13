package com.example.siteshield

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

enum class DataSaverMode(val displayName: String) {
    OFF("Off"),
    BALANCED("Balanced"),
    MAX("MAX"),
    ;

    fun next(): DataSaverMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStoredValue(value: String?): DataSaverMode =
            entries.firstOrNull { it.name == value } ?: OFF

        fun initialMode(hasLegacySettings: Boolean): DataSaverMode =
            if (hasLegacySettings) OFF else BALANCED
    }
}

class DataSaverModeStore(initialMode: DataSaverMode) {
    private val mode = AtomicReference(initialMode)

    fun snapshot(): DataSaverMode = mode.get()

    fun update(updatedMode: DataSaverMode) {
        mode.set(updatedMode)
    }
}

data class DataSaverPolicy(
    val blockExplicitPrefetch: Boolean = true,
    val blockNetworkImagesInMax: Boolean = false,
    val preserveMaxImagesForPageTypes: Set<PageType> = emptySet(),
) {
    fun blockNetworkImages(mode: DataSaverMode, pageType: PageType): Boolean =
        mode == DataSaverMode.MAX &&
            blockNetworkImagesInMax &&
            pageType !in preserveMaxImagesForPageTypes
}

data class DataSaverRequestContext(
    val method: String,
    val isForMainFrame: Boolean,
    val hasGesture: Boolean,
    val headers: Map<String, String>,
)

sealed interface DataSaverDecision {
    data object Allow : DataSaverDecision

    data class Block(val ruleId: String) : DataSaverDecision
}

object DataSaverEngine {
    fun decide(
        mode: DataSaverMode,
        policy: DataSaverPolicy,
        request: DataSaverRequestContext,
    ): DataSaverDecision {
        if (mode == DataSaverMode.OFF || request.isForMainFrame || !policy.blockExplicitPrefetch) {
            return DataSaverDecision.Allow
        }
        if (!request.method.equals("GET", ignoreCase = true)) return DataSaverDecision.Allow

        val explicitPrefetch = request.headers.entries
            .filter { (key, _) ->
                key.equals("Purpose", ignoreCase = true) ||
                    key.equals("Sec-Purpose", ignoreCase = true)
            }
            .map { (_, value) -> value.trim().lowercase(Locale.US) }
            .any { purpose -> purpose == "prefetch" || purpose.startsWith("prefetch;") }
        return if (explicitPrefetch) {
            DataSaverDecision.Block("explicit-prefetch")
        } else {
            DataSaverDecision.Allow
        }
    }
}

data class NetworkCounterSnapshot(
    val rxBytes: Long?,
    val txBytes: Long?,
)

fun interface NetworkCounterProvider {
    fun read(): NetworkCounterSnapshot
}

data class DataUsage(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
) {
    val totalBytes: Long
        get() = saturatingAdd(rxBytes, txBytes)

    operator fun plus(other: DataUsage): DataUsage = DataUsage(
        rxBytes = saturatingAdd(rxBytes, other.rxBytes),
        txBytes = saturatingAdd(txBytes, other.txBytes),
    )
}

data class DataUsageSnapshot(
    val session: DataUsage,
    val byProfile: Map<String, DataUsage>,
    val countersSupported: Boolean,
)

/**
 * Attributes UID counter deltas to the profile that was active during each interval.
 * This is interval attribution, not per-request or packet-level accounting.
 */
class DataUsageTracker(
    private val counterProvider: NetworkCounterProvider,
    initialProfileId: String,
) {
    private var activeProfileId = initialProfileId
    private var baseline = counterProvider.read().normalized()
    private var sessionUsage = DataUsage()
    private val profileUsage = linkedMapOf<String, DataUsage>()
    private var sawSupportedCounter = baseline.rxBytes != null || baseline.txBytes != null

    @Synchronized
    fun switchProfile(profileId: String) {
        if (profileId == activeProfileId) return
        flushActive()
        activeProfileId = profileId
    }

    @Synchronized
    fun snapshot(): DataUsageSnapshot {
        flushActive()
        return DataUsageSnapshot(
            session = sessionUsage,
            byProfile = profileUsage.toMap(),
            countersSupported = sawSupportedCounter,
        )
    }

    @Synchronized
    fun flush() {
        flushActive()
    }

    private fun flushActive() {
        val current = counterProvider.read().normalized()
        sawSupportedCounter = sawSupportedCounter || current.rxBytes != null || current.txBytes != null
        val delta = DataUsage(
            rxBytes = counterDelta(baseline.rxBytes, current.rxBytes),
            txBytes = counterDelta(baseline.txBytes, current.txBytes),
        )
        baseline = current
        if (delta.totalBytes == 0L) return
        sessionUsage += delta
        profileUsage[activeProfileId] = (profileUsage[activeProfileId] ?: DataUsage()) + delta
    }
}

fun formatDataBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safeBytes >= gb -> String.format(Locale.US, "%.1f GB", safeBytes / gb)
        safeBytes >= mb -> String.format(Locale.US, "%.1f MB", safeBytes / mb)
        safeBytes >= kb -> String.format(Locale.US, "%.1f KB", safeBytes / kb)
        else -> "$safeBytes B"
    }
}

private fun NetworkCounterSnapshot.normalized(): NetworkCounterSnapshot = NetworkCounterSnapshot(
    rxBytes = rxBytes?.takeIf { it >= 0 },
    txBytes = txBytes?.takeIf { it >= 0 },
)

private fun counterDelta(previous: Long?, current: Long?): Long =
    if (previous == null || current == null || current < previous) 0 else current - previous

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
