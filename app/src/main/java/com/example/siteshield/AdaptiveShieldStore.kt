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
        const val KEY_STATE = "adaptive_state_v2"
    }
}

object AdaptiveStateCodec {
    private const val VERSION = "v3"
    private const val PREVIOUS_VERSION = "v2"
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
                    record.siteScope.orEmpty(),
                    record.adEvidence.explicitAdSlotCount,
                    record.adEvidence.sponsoredAttributionCount,
                    record.adEvidence.adIframeCorrelationCount,
                    record.adEvidence.overlayAdCount,
                    record.adEvidence.repeatedLoaderCorrelationCount,
                    record.pathScoped,
                    record.promotionReason.orEmpty(),
                    record.safetyConflict.orEmpty(),
                ).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
    }

    fun decode(serialized: String?): List<AdaptiveRecord> {
        if (serialized.isNullOrBlank()) return emptyList()
        val lines = serialized.lineSequence().toList()
        return when (lines.firstOrNull()) {
            VERSION -> lines.drop(1).mapNotNull(::decodeV3Record)
            PREVIOUS_VERSION -> lines.drop(1).mapNotNull(::decodeV2Record)
            else -> emptyList()
        }
    }

    private fun decodeV3Record(line: String): AdaptiveRecord? = runCatching {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 29) return null
        val base = decodeBaseRecord(fields) ?: return null
        val promotionReason = fields[27].validatedOptionalReason() ?: return null
        val safetyConflict = fields[28].validatedOptionalReason() ?: return null
        base.copy(
            adEvidence = AdaptiveAdEvidence(
                explicitAdSlotCount = fields[21].toEvidenceCount() ?: return null,
                sponsoredAttributionCount = fields[22].toEvidenceCount() ?: return null,
                adIframeCorrelationCount = fields[23].toEvidenceCount() ?: return null,
                overlayAdCount = fields[24].toEvidenceCount() ?: return null,
                repeatedLoaderCorrelationCount = fields[25].toEvidenceCount() ?: return null,
            ),
            pathScoped = fields[26].toBooleanStrict(),
            promotionReason = promotionReason.takeIf(String::isNotBlank),
            safetyConflict = safetyConflict.takeIf(String::isNotBlank),
        )
    }.getOrNull()

    private fun decodeV2Record(line: String): AdaptiveRecord? = runCatching {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 21) return null
        decodeBaseRecord(fields)
    }.getOrNull()

    private fun decodeBaseRecord(fields: List<String>): AdaptiveRecord? {
        val profileId = fields[1].safeToken() ?: return null
        val host = fields[4].normalizedHost() ?: return null
        val path = fields[5].takeIf(String::isNotBlank)?.let(::sanitizeAdaptivePath)
        val siteScope = fields[20]
            .takeIf(String::isNotBlank)
            ?.normalizedHost()
            ?.takeIf { it.length <= 253 && it.matches(Regex("[a-z0-9.-]+")) }
        if (profileId == GenericWebProfile.profile.id && siteScope == null) return null
        if (profileId != GenericWebProfile.profile.id && siteScope != null) return null
        return AdaptiveRecord(
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
            siteScope = siteScope,
        )
    }

    private fun String.safeId(): String? =
        takeIf { length in 1..500 && none { char -> char == FIELD_SEPARATOR || char == '\n' || char == '\r' } }

    private fun String.safeToken(): String? =
        takeIf { length in 1..80 && matches(Regex("[a-zA-Z0-9._-]+")) }

    private fun String.validatedOptionalReason(): String? =
        if (isBlank()) "" else takeIf { length <= 80 && matches(Regex("[a-z0-9+_-]+")) }

    private fun String.toEvidenceCount(): Int? = toIntOrNull()?.takeIf { it in 0..MAX_EVIDENCE_COUNT }

    private fun String.toNonNegativeInt(): Int = toInt().coerceAtLeast(0)
    private fun String.toNonNegativeLong(): Long = toLong().coerceAtLeast(0)

    private const val MAX_EVIDENCE_COUNT = 10_000
}

class AdaptiveShieldController(
    persistence: AdaptiveStatePersistence,
    initialMode: AdaptiveShieldMode,
    private val profileById: (String) -> SiteProfile,
    private val onEvent: (DebugEvent) -> Unit,
    private val clock: AdaptiveClock = AdaptiveClock(System::currentTimeMillis),
    private val persistDelayMs: Long = 1_500L,
) : AutoCloseable {
    private data class RecentRequest(
        val scope: AdaptiveScope,
        val host: String,
        val path: String,
        val blockedByStaticRule: Boolean,
        val correlatedWithRedirect: Boolean,
        val resourceKind: AdaptiveResourceKind,
        val observedAtMs: Long,
    )

    private val persistence = persistence
    private val mode = AtomicReference(initialMode)
    private val engine = AdaptiveShieldEngine(persistence.load())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "adaptive-shield-store").apply { isDaemon = true }
    }
    private val persistLock = Any()
    private var scheduledPersist: ScheduledFuture<*>? = null
    private val recentRequests = ArrayDeque<RecentRequest>()

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
        val scope = adaptiveScope(profile, pageUrl) ?: return
        val host = requestUrl.hostFromUrl() ?: return
        val nowMs = clock.nowMs()
        val path = sanitizeAdaptivePath(requestUrl.toUriOrNull()?.path)
        val redirectEvidence = engine.hasRedirectEvidence(scope, host)
        synchronized(recentRequests) {
            recentRequests.removeIf { nowMs - it.observedAtMs > DOM_CORRELATION_WINDOW_MS }
            while (recentRequests.size >= MAX_RECENT_REQUESTS) recentRequests.removeFirst()
            recentRequests.addLast(
                RecentRequest(scope, host, path, blockedByStaticRule, redirectEvidence, resourceKind, nowMs),
            )
        }
        val observation = AdaptiveObservationFactory.request(
            profile = profile,
            pageUrl = pageUrl,
            requestUrl = requestUrl,
            blockedByStaticRule = blockedByStaticRule,
            correlatedWithRedirect = redirectEvidence,
            functionalEvidence = profile.adaptivePolicy.isFunctionalEvidence(host, resourceKind),
            resourceKind = resourceKind,
            observedAtMs = nowMs,
        ) ?: return
        record(observation, profile.adaptivePolicy)
    }

    fun observeDomAdEvidence(
        profile: SiteProfile,
        pageUrl: String?,
        reports: List<AdaptiveDomAdReport>,
    ) {
        if (mode.get() == AdaptiveShieldMode.OFF || !profile.adaptivePolicy.enabled) return
        val scope = adaptiveScope(profile, pageUrl) ?: return
        val nowMs = clock.nowMs()
        val batch = reports.take(MAX_DOM_REPORTS_PER_BATCH)
        val hasReportedLoader = batch.any { it.role == AdaptiveAdResourceRole.LOADER }
        val hasCorrelatedAdFrame = batch.any { report ->
            report.role == AdaptiveAdResourceRole.IFRAME && synchronized(recentRequests) {
                recentRequests.any {
                    nowMs - it.observedAtMs <= DOM_CORRELATION_WINDOW_MS &&
                        it.scope == scope && it.host == report.host && it.path == report.path
                }
            }
        }
        batch.forEach { report ->
            if (report.role == AdaptiveAdResourceRole.STRUCTURE) {
                record(
                    AdaptiveAdObservation(
                        profileId = profile.id,
                        host = scope.siteScope ?: report.host,
                        path = null,
                        pageType = profile.pageTypeForAdaptive(pageUrl),
                        observedAtMs = nowMs,
                        evidence = report.evidence(),
                        thirdParty = false,
                        pathScoped = false,
                        siteScope = scope.siteScope,
                    ),
                    profile.adaptivePolicy,
                )
                if (!hasReportedLoader && hasCorrelatedAdFrame) {
                    inferSingleRecentLoader(scope, profile, report, nowMs)?.let { inferred ->
                        record(inferred, profile.adaptivePolicy)
                    }
                }
                return@forEach
            }
            val recent = synchronized(recentRequests) {
                recentRequests.removeIf { nowMs - it.observedAtMs > DOM_CORRELATION_WINDOW_MS }
                recentRequests.lastOrNull {
                    it.scope == scope && it.host == report.host && it.path == report.path
                }
            } ?: return@forEach
            val functionalConflict = profile.adaptivePolicy.protectedHosts.any { it.matches(report.host) } ||
                isAdaptiveAccountNavigation("https://${report.host}${report.path}")
            record(
                AdaptiveAdObservation(
                    profileId = profile.id,
                    host = report.host,
                    path = report.path,
                    pageType = profile.pageTypeForAdaptive(pageUrl),
                    observedAtMs = nowMs,
                    evidence = report.evidence(),
                    thirdParty = report.host != scope.siteScope &&
                        profile.allowedHosts.none { it.matches(report.host) },
                    pathScoped = report.pathScoped || report.host == scope.siteScope,
                    functionalConflict = functionalConflict,
                    blockedByStaticRule = recent.blockedByStaticRule,
                    correlatedWithRedirect = recent.correlatedWithRedirect,
                    siteScope = scope.siteScope,
                ),
                profile.adaptivePolicy,
            )
        }
    }

    private fun inferSingleRecentLoader(
        scope: AdaptiveScope,
        profile: SiteProfile,
        structure: AdaptiveDomAdReport,
        nowMs: Long,
    ): AdaptiveAdObservation? {
        if (!structure.explicitAdSlot && !structure.sponsoredAttribution) return null
        val scripts = synchronized(recentRequests) {
            recentRequests.filter {
                nowMs - it.observedAtMs <= DOM_CORRELATION_WINDOW_MS &&
                    it.scope == scope && it.resourceKind == AdaptiveResourceKind.OTHER &&
                    it.host != scope.siteScope && it.path.matches(Regex(".*\\.(m?js)$", RegexOption.IGNORE_CASE)) &&
                    profile.adaptivePolicy.protectedHosts.none { pattern -> pattern.matches(it.host) }
            }.distinctBy { "${it.host}${it.path}" }
        }
        val script = scripts.singleOrNull() ?: return null
        return AdaptiveAdObservation(
            profileId = profile.id,
            host = script.host,
            path = script.path,
            pageType = profile.pageTypeForAdaptive(scope.siteScope?.let { "https://$it/" }),
            observedAtMs = nowMs,
            evidence = AdaptiveAdEvidence(
                explicitAdSlotCount = if (structure.explicitAdSlot) 1 else 0,
                sponsoredAttributionCount = if (structure.sponsoredAttribution) 1 else 0,
                adIframeCorrelationCount = 1,
                overlayAdCount = if (structure.overlayLayout) 1 else 0,
                repeatedLoaderCorrelationCount = 1,
            ),
            thirdParty = true,
            pathScoped = true,
            blockedByStaticRule = script.blockedByStaticRule,
            correlatedWithRedirect = script.correlatedWithRedirect,
            siteScope = scope.siteScope,
        )
    }

    fun decideRequest(
        profile: SiteProfile,
        pageUrl: String?,
        requestUrl: String,
        resourceKind: AdaptiveResourceKind,
        blockerEnabled: Boolean,
        userInitiated: Boolean = false,
    ): AdaptiveDecision {
        val scope = adaptiveScope(profile, pageUrl) ?: return AdaptiveDecision.Allow
        return engine.decideRequest(
            scope = scope,
            policy = profile.adaptivePolicy,
            url = requestUrl,
            resourceKind = resourceKind,
            userInitiated = userInitiated,
            blockerEnabled = blockerEnabled,
            mode = mode.get(),
            nowMs = clock.nowMs(),
        )
    }

    fun decideNavigation(
        profile: SiteProfile,
        sourceUrl: String?,
        targetUrl: String,
        userInitiated: Boolean,
        blockerEnabled: Boolean,
    ): AdaptiveDecision {
        val scope = adaptiveScope(profile, sourceUrl) ?: return AdaptiveDecision.Allow
        return engine.decideNavigation(
            scope = scope,
            policy = profile.adaptivePolicy,
            targetUrl = targetUrl,
            userInitiated = userInitiated,
            blockerEnabled = blockerEnabled,
            mode = mode.get(),
            nowMs = clock.nowMs(),
        )
    }

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

    fun records(scope: AdaptiveScope): List<AdaptiveRecord> =
        engine.snapshot(clock.nowMs()).filter { it.scope() == scope }

    fun summary(scope: AdaptiveScope): AdaptiveStatusSummary = engine.summary(scope, clock.nowMs())

    fun forget(scope: AdaptiveScope, ruleId: String? = null) {
        engine.forget(scope, ruleId)
        persistNow()
    }

    fun disable(scope: AdaptiveScope, ruleId: String) {
        val disabled = engine.disable(scope, ruleId, clock.nowMs()) ?: return
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

    private companion object {
        const val MAX_RECENT_REQUESTS = 128
        const val MAX_DOM_REPORTS_PER_BATCH = 32
        const val DOM_CORRELATION_WINDOW_MS = 20_000L
    }
}

fun AdaptiveRecord.safeDiagnostic(): String =
    "scope=${scope().diagnosticName}, profile=$profileId, host=$host, type=$type, state=$state, " +
        "count=$occurrenceCount, confidence=$confidence, static=$staticBlockCount, " +
        "thirdParty=$thirdPartyCount, redirects=$redirectCorrelationCount, " +
        "adSlot=${adEvidence.explicitAdSlotCount}, sponsored=${adEvidence.sponsoredAttributionCount}, " +
        "iframes=${adEvidence.adIframeCorrelationCount}, overlays=${adEvidence.overlayAdCount}, " +
        "loaders=${adEvidence.repeatedLoaderCorrelationCount}, functional=$functionalEvidenceCount, " +
        "promotion=${promotionReason ?: "none"}, safety=${safetyConflict ?: "none"}, ruleId=$id"

private fun SiteProfile.pageTypeForAdaptive(url: String?): PageType =
    pageTypeRules.firstOrNull { url != null && it.matches(url) }?.pageType ?: PageType.UNKNOWN
