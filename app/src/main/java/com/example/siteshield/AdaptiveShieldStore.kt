package com.example.siteshield

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun interface AdaptiveClock {
    fun nowMs(): Long
}

interface AdaptiveStatePersistence {
    fun load(): List<AdaptiveRecord>
    fun save(records: List<AdaptiveRecord>)
}

class SharedPreferencesAdaptiveStatePersistence(context: Context) : AdaptiveStatePersistence {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): List<AdaptiveRecord> =
        AdaptiveStateCodec.decode(preferences.getString(KEY_STATE, null))

    override fun save(records: List<AdaptiveRecord>) {
        preferences.edit().putString(KEY_STATE, AdaptiveStateCodec.encode(records)).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "site_shield_adaptive"
        const val KEY_STATE = "adaptive_state_v1"
    }
}

object AdaptiveStateCodec {
    private const val VERSION = "v1"
    private const val FIELD_SEPARATOR = '\t'

    fun encode(records: List<AdaptiveRecord>): String = buildString {
        append(VERSION)
        records.forEach { record ->
            append('\n')
            append(
                listOf(
                    record.id,
                    record.profileId,
                    record.type.name,
                    record.riskTier.name,
                    record.host,
                    record.path.orEmpty(),
                    record.state.name,
                    record.occurrenceCount,
                    record.popupCount,
                    record.sourcePolicyBlockCount,
                    record.staticBlockCount,
                    record.thirdPartyCount,
                    record.redirectCorrelationCount,
                    record.functionalEvidenceCount,
                    record.firstSeenAtMs,
                    record.lastSeenAtMs,
                    record.learnedAtMs ?: -1L,
                    record.rejectedAtMs ?: -1L,
                    record.score,
                    record.confidence,
                ).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
    }

    fun decode(serialized: String?): List<AdaptiveRecord> {
        if (serialized.isNullOrBlank()) return emptyList()
        val lines = serialized.lineSequence().toList()
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull(::decodeRecord)
    }

    private fun decodeRecord(line: String): AdaptiveRecord? = runCatching {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 20) return null
        val profileId = fields[1].safeToken() ?: return null
        val host = fields[4].normalizedHost() ?: return null
        val path = fields[5].takeIf(String::isNotBlank)?.let(::sanitizeAdaptivePath)
        AdaptiveRecord(
            id = fields[0].safeId() ?: return null,
            profileId = profileId,
            type = AdaptiveCandidateType.valueOf(fields[2]),
            riskTier = AdaptiveRiskTier.valueOf(fields[3]),
            host = host,
            path = path,
            state = AdaptiveCandidateState.valueOf(fields[6]),
            occurrenceCount = fields[7].toNonNegativeInt(),
            popupCount = fields[8].toNonNegativeInt(),
            sourcePolicyBlockCount = fields[9].toNonNegativeInt(),
            staticBlockCount = fields[10].toNonNegativeInt(),
            thirdPartyCount = fields[11].toNonNegativeInt(),
            redirectCorrelationCount = fields[12].toNonNegativeInt(),
            functionalEvidenceCount = fields[13].toNonNegativeInt(),
            firstSeenAtMs = fields[14].toNonNegativeLong(),
            lastSeenAtMs = fields[15].toNonNegativeLong(),
            learnedAtMs = fields[16].toLong().takeIf { it >= 0 },
            rejectedAtMs = fields[17].toLong().takeIf { it >= 0 },
            score = fields[18].toNonNegativeInt(),
            confidence = fields[19].toInt().coerceIn(0, 100),
        )
    }.getOrNull()

    private fun String.safeId(): String? =
        takeIf { length in 1..500 && none { char -> char == FIELD_SEPARATOR || char == '\n' || char == '\r' } }

    private fun String.safeToken(): String? =
        takeIf { length in 1..80 && matches(Regex("[a-zA-Z0-9._-]+")) }

    private fun String.toNonNegativeInt(): Int = toInt().coerceAtLeast(0)
    private fun String.toNonNegativeLong(): Long = toLong().coerceAtLeast(0)
}

class AdaptiveShieldController(
    persistence: AdaptiveStatePersistence,
    initialMode: AdaptiveShieldMode,
    private val profileById: (String) -> SiteProfile,
    private val onEvent: (DebugEvent) -> Unit,
    private val clock: AdaptiveClock = AdaptiveClock(System::currentTimeMillis),
    private val persistDelayMs: Long = 1_500L,
) : AutoCloseable {
    private val persistence = persistence
    private val mode = AtomicReference(initialMode)
    private val engine = AdaptiveShieldEngine(persistence.load())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "adaptive-shield-store").apply { isDaemon = true }
    }
    private val persistLock = Any()
    private var scheduledPersist: ScheduledFuture<*>? = null

    fun mode(): AdaptiveShieldMode = mode.get()

    fun updateMode(updatedMode: AdaptiveShieldMode) {
        mode.set(updatedMode)
        engine.reconsider({ profileById(it).adaptivePolicy }, updatedMode, clock.nowMs())
        schedulePersist()
    }

    fun observeNavigation(
        profile: SiteProfile,
        sourceUrl: String?,
        targetUrl: String,
        popup: Boolean,
        blockedBySourcePolicy: Boolean,
    ) {
        val observation = AdaptiveObservationFactory.navigation(
            profile = profile,
            sourceUrl = sourceUrl,
            targetUrl = targetUrl,
            popup = popup,
            blockedBySourcePolicy = blockedBySourcePolicy,
            observedAtMs = clock.nowMs(),
        ) ?: return
        record(observation, profile.adaptivePolicy)
    }

    fun observeRequest(
        profile: SiteProfile,
        pageUrl: String?,
        requestUrl: String,
        blockedByStaticRule: Boolean,
        resourceKind: AdaptiveResourceKind,
    ) {
        val host = requestUrl.hostFromUrl() ?: return
        val observation = AdaptiveObservationFactory.request(
            profile = profile,
            pageUrl = pageUrl,
            requestUrl = requestUrl,
            blockedByStaticRule = blockedByStaticRule,
            correlatedWithRedirect = engine.hasRedirectEvidence(profile.id, host),
            functionalEvidence = profile.adaptivePolicy.protects(host, resourceKind),
            resourceKind = resourceKind,
            observedAtMs = clock.nowMs(),
        ) ?: return
        record(observation, profile.adaptivePolicy)
    }

    fun decideRequest(
        profile: SiteProfile,
        pageUrl: String?,
        requestUrl: String,
        resourceKind: AdaptiveResourceKind,
        blockerEnabled: Boolean,
    ): AdaptiveDecision = engine.decide(
        profile = profile,
        url = requestUrl,
        pageType = profile.pageTypeRules.firstOrNull { pageUrl != null && it.matches(pageUrl) }?.pageType
            ?: PageType.UNKNOWN,
        resourceKind = resourceKind,
        blockerEnabled = blockerEnabled,
        mode = mode.get(),
        nowMs = clock.nowMs(),
    )

    fun reportPageHealth(health: AdaptivePageHealth) {
        val rejected = engine.reportPageHealth(health, clock.nowMs())
        rejected.forEach { record ->
            onEvent(
                DebugEvent(
                    category = DebugEventCategory.ADAPTIVE_ROLLBACK,
                    message = "Adaptive rule suspended after page-health failure",
                    detail = record.safeDiagnostic(),
                ),
            )
        }
        if (rejected.isNotEmpty()) schedulePersist()
    }

    fun records(profileId: String): List<AdaptiveRecord> =
        engine.snapshot(clock.nowMs()).filter { it.profileId == profileId }

    fun summary(profileId: String): AdaptiveStatusSummary = engine.summary(profileId, clock.nowMs())

    fun forget(profileId: String, ruleId: String? = null) {
        engine.forget(profileId, ruleId)
        persistNow()
    }

    fun disable(profileId: String, ruleId: String) {
        val disabled = engine.disable(profileId, ruleId, clock.nowMs()) ?: return
        onEvent(
            DebugEvent(
                category = DebugEventCategory.ADAPTIVE_ROLLBACK,
                message = "Adaptive rule disabled by user",
                detail = disabled.safeDiagnostic(),
            ),
        )
        persistNow()
    }

    override fun close() {
        synchronized(persistLock) { scheduledPersist?.cancel(false) }
        persistNow()
        executor.shutdown()
    }

    private fun record(observation: AdaptiveObservation, policy: AdaptivePolicy) {
        val change = engine.observe(observation, policy, mode.get()) ?: return
        val category = when {
            change.current.state == AdaptiveCandidateState.LEARNED &&
                change.previous?.state != AdaptiveCandidateState.LEARNED -> DebugEventCategory.ADAPTIVE_PROMOTE
            change.current.state == AdaptiveCandidateState.CANDIDATE &&
                change.previous?.state != AdaptiveCandidateState.CANDIDATE -> DebugEventCategory.ADAPTIVE_CANDIDATE
            else -> DebugEventCategory.ADAPTIVE_OBSERVE
        }
        val shouldLog = category != DebugEventCategory.ADAPTIVE_OBSERVE ||
            change.previous == null ||
            change.current.occurrenceCount in setOf(3, 5, 10, 25, 50)
        if (shouldLog) {
            onEvent(
                DebugEvent(
                    category = category,
                    message = "Adaptive ${change.current.state.name.lowercase()} evidence",
                    detail = change.current.safeDiagnostic(),
                ),
            )
        }
        schedulePersist()
    }

    private fun schedulePersist() {
        synchronized(persistLock) {
            if (scheduledPersist?.isDone == false) return
            scheduledPersist = executor.schedule({ persistNow() }, persistDelayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun persistNow() {
        persistence.save(engine.snapshot(clock.nowMs()))
    }
}

fun AdaptiveRecord.safeDiagnostic(): String =
    "profile=$profileId, host=$host, type=$type, state=$state, count=$occurrenceCount, " +
        "confidence=$confidence, ruleId=$id"
