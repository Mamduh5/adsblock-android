package com.example.siteshield

import java.util.Locale

enum class AdaptiveShieldMode(val displayName: String) {
    OFF("Off"),
    LEARN("Learn"),
    AUTO_SAFE("Auto Safe"),
    ;

    fun next(): AdaptiveShieldMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStoredValue(value: String?): AdaptiveShieldMode =
            entries.firstOrNull { it.name == value } ?: LEARN

        fun initialMode(): AdaptiveShieldMode = LEARN
    }
}

enum class AdaptiveCandidateType {
    OFFSITE_REDIRECT_HOST,
    THIRD_PARTY_REQUEST_HOST,
    FIRST_PARTY_LOADER,
    DOM_STRUCTURE,
}

enum class AdaptiveRiskTier {
    LOW_RISK,
    MEDIUM_RISK,
    HIGH_RISK,
}

enum class AdaptiveCandidateState {
    OBSERVED,
    CANDIDATE,
    LEARNED,
    DORMANT,
    REJECTED,
}

enum class AdaptiveResourceKind {
    OTHER,
    IMAGE,
    VIDEO,
    FONT,
}

data class AdaptivePolicy(
    val enabled: Boolean = false,
    val observeOffsiteNavigations: Boolean = false,
    val observeThirdPartyRequests: Boolean = false,
    val autoPromoteTypes: Set<AdaptiveCandidateType> = emptySet(),
    val protectedHosts: List<HostPattern> = emptyList(),
    val firstPartyLoaderPathPrefixes: List<String> = emptyList(),
) {
    fun protects(host: String?, kind: AdaptiveResourceKind): Boolean =
        kind in setOf(AdaptiveResourceKind.IMAGE, AdaptiveResourceKind.VIDEO, AdaptiveResourceKind.FONT) ||
            protectedHosts.any { it.matches(host) }
}

data class AdaptiveLearningConfig(
    val candidateThreshold: Int = 70,
    val candidateMinimumOccurrences: Int = 3,
    val redirectAutoThreshold: Int = 150,
    val redirectAutoMinimumOccurrences: Int = 3,
    val thirdPartyAutoThreshold: Int = 200,
    val thirdPartyAutoMinimumOccurrences: Int = 5,
    val loaderAutoThreshold: Int = 270,
    val loaderAutoMinimumOccurrences: Int = 6,
    val popupWeight: Int = 50,
    val offsiteNavigationWeight: Int = 30,
    val sourcePolicyBlockWeight: Int = 20,
    val staticRequestBlockWeight: Int = 35,
    val thirdPartyWeight: Int = 8,
    val correlatedRedirectWeight: Int = 35,
    val exactLoaderPathWeight: Int = 60,
    val domObservationWeight: Int = 5,
    val firstPartyPenalty: Int = 15,
    val functionalEvidencePenalty: Int = 80,
    val candidateExpiryMs: Long = 14L * DAY_MS,
    val learnedDormancyMs: Long = 30L * DAY_MS,
    val learnedRetirementMs: Long = 60L * DAY_MS,
    val rollbackWindowMs: Long = 15_000L,
    val maxCandidatesPerProfile: Int = 64,
    val maxLearnedRulesPerProfile: Int = 24,
) {
    init {
        require(candidateMinimumOccurrences >= 2)
        require(redirectAutoMinimumOccurrences >= candidateMinimumOccurrences)
        require(thirdPartyAutoMinimumOccurrences >= candidateMinimumOccurrences)
        require(loaderAutoMinimumOccurrences >= candidateMinimumOccurrences)
        require(candidateExpiryMs > 0 && learnedDormancyMs > candidateExpiryMs)
        require(learnedRetirementMs > learnedDormancyMs)
        require(maxCandidatesPerProfile > 0 && maxLearnedRulesPerProfile > 0)
    }

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

sealed interface AdaptiveObservation {
    val profileId: String
    val host: String
    val path: String?
    val pageType: PageType
    val observedAtMs: Long
}

data class AdaptiveNavigationObservation(
    override val profileId: String,
    override val host: String,
    override val pageType: PageType,
    override val observedAtMs: Long,
    val popup: Boolean,
    val blockedBySourcePolicy: Boolean,
    override val path: String? = null,
) : AdaptiveObservation

data class AdaptiveRequestObservation(
    override val profileId: String,
    override val host: String,
    override val path: String?,
    override val pageType: PageType,
    override val observedAtMs: Long,
    val thirdParty: Boolean,
    val blockedByStaticRule: Boolean,
    val correlatedWithRedirect: Boolean,
    val functionalEvidence: Boolean,
    val loaderPath: Boolean,
    val resourceKind: AdaptiveResourceKind,
) : AdaptiveObservation

data class AdaptiveDomObservation(
    override val profileId: String,
    override val host: String,
    override val path: String?,
    override val pageType: PageType,
    override val observedAtMs: Long,
    val fingerprint: String,
) : AdaptiveObservation

data class AdaptiveRecord(
    val id: String,
    val profileId: String,
    val type: AdaptiveCandidateType,
    val riskTier: AdaptiveRiskTier,
    val host: String,
    val path: String?,
    val state: AdaptiveCandidateState,
    val occurrenceCount: Int,
    val popupCount: Int,
    val sourcePolicyBlockCount: Int,
    val staticBlockCount: Int,
    val thirdPartyCount: Int,
    val redirectCorrelationCount: Int,
    val functionalEvidenceCount: Int,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
    val learnedAtMs: Long?,
    val rejectedAtMs: Long?,
    val score: Int,
    val confidence: Int,
)

sealed interface AdaptiveDecision {
    data object Allow : AdaptiveDecision

    data class Block(
        val ruleId: String,
        val confidence: Int,
        val type: AdaptiveCandidateType,
    ) : AdaptiveDecision
}

data class AdaptivePageHealth(
    val profileId: String,
    val pageType: PageType,
    val healthy: Boolean,
    val readerContainerPresent: Boolean = true,
    val chapterImageCount: Int = 1,
    val chapterNavigationPresent: Boolean = true,
)

data class AdaptiveStatusSummary(
    val observed: Int,
    val candidates: Int,
    val learnedActive: Int,
    val dormant: Int,
    val rejected: Int,
)

data class AdaptiveChange(
    val previous: AdaptiveRecord?,
    val current: AdaptiveRecord,
)

class AdaptiveShieldEngine(
    initialRecords: List<AdaptiveRecord> = emptyList(),
    private val config: AdaptiveLearningConfig = AdaptiveLearningConfig(),
) {
    private val records = linkedMapOf<String, AdaptiveRecord>()
    private val recentEnforcements = linkedMapOf<String, Long>()

    init {
        initialRecords.forEach { record -> records[record.id] = record }
    }

    @Synchronized
    fun observe(
        observation: AdaptiveObservation,
        policy: AdaptivePolicy,
        mode: AdaptiveShieldMode,
    ): AdaptiveChange? {
        if (mode == AdaptiveShieldMode.OFF || !policy.enabled) return null
        if (observation is AdaptiveRequestObservation && policy.protects(observation.host, observation.resourceKind)) {
            return null
        }

        maintain(observation.observedAtMs)
        val type = observation.candidateType()
        val path = when (type) {
            AdaptiveCandidateType.FIRST_PARTY_LOADER -> observation.path
            AdaptiveCandidateType.DOM_STRUCTURE ->
                (observation as AdaptiveDomObservation).fingerprint.safeDomFingerprint()
            else -> null
        }
        val id = adaptiveRuleId(observation.profileId, type, observation.host, path)
        val previous = records[id]
        val revived = previous?.state == AdaptiveCandidateState.DORMANT
        val base = previous ?: AdaptiveRecord(
            id = id,
            profileId = observation.profileId,
            type = type,
            riskTier = type.riskTier(),
            host = observation.host,
            path = path,
            state = AdaptiveCandidateState.OBSERVED,
            occurrenceCount = 0,
            popupCount = 0,
            sourcePolicyBlockCount = 0,
            staticBlockCount = 0,
            thirdPartyCount = 0,
            redirectCorrelationCount = 0,
            functionalEvidenceCount = 0,
            firstSeenAtMs = observation.observedAtMs,
            lastSeenAtMs = observation.observedAtMs,
            learnedAtMs = null,
            rejectedAtMs = null,
            score = 0,
            confidence = 0,
        )
        if (base.state == AdaptiveCandidateState.REJECTED) return null

        val counted = base.withObservation(observation)
        val score = score(counted)
        val confidence = confidence(score, autoThreshold(type))
        val candidate = counted.copy(
            score = score,
            confidence = confidence,
            state = when {
                revived && eligibleForAuto(counted.copy(score = score), policy) -> AdaptiveCandidateState.LEARNED
                counted.occurrenceCount >= config.candidateMinimumOccurrences && score >= config.candidateThreshold ->
                    AdaptiveCandidateState.CANDIDATE
                else -> AdaptiveCandidateState.OBSERVED
            },
        )
        val promoted = if (
            mode == AdaptiveShieldMode.AUTO_SAFE &&
            candidate.state != AdaptiveCandidateState.LEARNED &&
            eligibleForAuto(candidate, policy) &&
            learnedCount(observation.profileId) < config.maxLearnedRulesPerProfile
        ) {
            candidate.copy(state = AdaptiveCandidateState.LEARNED, learnedAtMs = observation.observedAtMs)
        } else {
            candidate
        }
        records[id] = promoted
        trimProfile(observation.profileId)
        return AdaptiveChange(previous, promoted)
    }

    @Synchronized
    fun reconsider(policyByProfile: (String) -> AdaptivePolicy, mode: AdaptiveShieldMode, nowMs: Long) {
        maintain(nowMs)
        if (mode != AdaptiveShieldMode.AUTO_SAFE) return
        records.replaceAll { _, record ->
            if (
                record.state == AdaptiveCandidateState.CANDIDATE &&
                eligibleForAuto(record, policyByProfile(record.profileId)) &&
                learnedCount(record.profileId) < config.maxLearnedRulesPerProfile
            ) {
                record.copy(state = AdaptiveCandidateState.LEARNED, learnedAtMs = nowMs)
            } else {
                record
            }
        }
    }

    @Synchronized
    fun decide(
        profile: SiteProfile,
        url: String,
        pageType: PageType,
        resourceKind: AdaptiveResourceKind,
        blockerEnabled: Boolean,
        mode: AdaptiveShieldMode,
        nowMs: Long,
    ): AdaptiveDecision {
        if (!blockerEnabled || mode != AdaptiveShieldMode.AUTO_SAFE || !profile.adaptivePolicy.enabled) {
            return AdaptiveDecision.Allow
        }
        val parsed = url.toUriOrNull() ?: return AdaptiveDecision.Allow
        val host = parsed.host.normalizedHost() ?: return AdaptiveDecision.Allow
        if (profile.adaptivePolicy.protects(host, resourceKind)) return AdaptiveDecision.Allow
        maintain(nowMs)
        val path = sanitizeAdaptivePath(parsed.path)
        val match = records.values.firstOrNull { record ->
                record.profileId == profile.id &&
                record.state == AdaptiveCandidateState.LEARNED &&
                record.type in profile.adaptivePolicy.autoPromoteTypes &&
                record.host == host &&
                when (record.type) {
                    AdaptiveCandidateType.FIRST_PARTY_LOADER -> record.path == path
                    AdaptiveCandidateType.DOM_STRUCTURE -> false
                    else -> true
                }
        } ?: return AdaptiveDecision.Allow
        recentEnforcements[match.id] = nowMs
        return AdaptiveDecision.Block(match.id, match.confidence, match.type)
    }

    @Synchronized
    fun reportPageHealth(health: AdaptivePageHealth, nowMs: Long): List<AdaptiveRecord> {
        if (health.healthy) return emptyList()
        val rejected = mutableListOf<AdaptiveRecord>()
        recentEnforcements.entries.removeIf { (ruleId, enforcedAt) ->
            val record = records[ruleId]
            val withinWindow = nowMs - enforcedAt in 0..config.rollbackWindowMs
            if (withinWindow && record?.profileId == health.profileId && record.state == AdaptiveCandidateState.LEARNED) {
                val updated = record.copy(
                    state = AdaptiveCandidateState.REJECTED,
                    rejectedAtMs = nowMs,
                )
                records[ruleId] = updated
                rejected += updated
            }
            !withinWindow || record == null || record.profileId == health.profileId
        }
        return rejected
    }

    @Synchronized
    fun hasRedirectEvidence(profileId: String, host: String): Boolean =
        records.values.any {
            it.profileId == profileId &&
                it.host == host.normalizedHost() &&
                it.type == AdaptiveCandidateType.OFFSITE_REDIRECT_HOST &&
                it.sourcePolicyBlockCount > 0
        }

    @Synchronized
    fun snapshot(nowMs: Long): List<AdaptiveRecord> {
        maintain(nowMs)
        return records.values.sortedWith(
            compareByDescending<AdaptiveRecord> { it.state == AdaptiveCandidateState.LEARNED }
                .thenByDescending { it.confidence }
                .thenByDescending { it.lastSeenAtMs },
        )
    }

    @Synchronized
    fun summary(profileId: String, nowMs: Long): AdaptiveStatusSummary {
        val profileRecords = snapshot(nowMs).filter { it.profileId == profileId }
        return AdaptiveStatusSummary(
            observed = profileRecords.count { it.state == AdaptiveCandidateState.OBSERVED },
            candidates = profileRecords.count { it.state == AdaptiveCandidateState.CANDIDATE },
            learnedActive = profileRecords.count { it.state == AdaptiveCandidateState.LEARNED },
            dormant = profileRecords.count { it.state == AdaptiveCandidateState.DORMANT },
            rejected = profileRecords.count { it.state == AdaptiveCandidateState.REJECTED },
        )
    }

    @Synchronized
    fun forget(profileId: String, ruleId: String? = null) {
        records.entries.removeIf { (_, record) ->
            record.profileId == profileId && (ruleId == null || record.id == ruleId)
        }
        recentEnforcements.keys.removeIf { it !in records }
    }

    @Synchronized
    fun disable(profileId: String, ruleId: String, nowMs: Long): AdaptiveRecord? {
        val record = records[ruleId]?.takeIf { it.profileId == profileId } ?: return null
        val disabled = record.copy(state = AdaptiveCandidateState.REJECTED, rejectedAtMs = nowMs)
        records[ruleId] = disabled
        recentEnforcements.remove(ruleId)
        return disabled
    }

    private fun maintain(nowMs: Long) {
        records.replaceAll { _, record ->
            val age = (nowMs - record.lastSeenAtMs).coerceAtLeast(0)
            if (record.state == AdaptiveCandidateState.LEARNED && age >= config.learnedDormancyMs) {
                record.copy(state = AdaptiveCandidateState.DORMANT)
            } else {
                record
            }
        }
        records.entries.removeIf { (_, record) ->
            val age = (nowMs - record.lastSeenAtMs).coerceAtLeast(0)
            when (record.state) {
                AdaptiveCandidateState.LEARNED -> false
                AdaptiveCandidateState.DORMANT -> age >= config.learnedRetirementMs
                AdaptiveCandidateState.REJECTED -> age >= config.learnedRetirementMs
                else -> age >= config.candidateExpiryMs
            }
        }
        recentEnforcements.entries.removeIf { (_, at) -> nowMs - at > config.rollbackWindowMs }
    }

    private fun eligibleForAuto(record: AdaptiveRecord, policy: AdaptivePolicy): Boolean {
        if (record.type !in policy.autoPromoteTypes || record.riskTier == AdaptiveRiskTier.HIGH_RISK) return false
        if (record.functionalEvidenceCount > 0) return false
        return when (record.type) {
            AdaptiveCandidateType.OFFSITE_REDIRECT_HOST ->
                record.occurrenceCount >= config.redirectAutoMinimumOccurrences &&
                    record.score >= config.redirectAutoThreshold &&
                    record.sourcePolicyBlockCount >= config.redirectAutoMinimumOccurrences
            AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST ->
                record.occurrenceCount >= config.thirdPartyAutoMinimumOccurrences &&
                    record.score >= config.thirdPartyAutoThreshold &&
                    record.redirectCorrelationCount >= 2
            AdaptiveCandidateType.FIRST_PARTY_LOADER ->
                record.path != null &&
                    record.occurrenceCount >= config.loaderAutoMinimumOccurrences &&
                    record.score >= config.loaderAutoThreshold
            AdaptiveCandidateType.DOM_STRUCTURE -> false
        }
    }

    private fun score(record: AdaptiveRecord): Int {
        val positive = when (record.type) {
            AdaptiveCandidateType.OFFSITE_REDIRECT_HOST ->
                record.popupCount * config.popupWeight +
                    (record.occurrenceCount - record.popupCount).coerceAtLeast(0) *
                    config.offsiteNavigationWeight +
                    record.sourcePolicyBlockCount * config.sourcePolicyBlockWeight
            AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST ->
                record.staticBlockCount * config.staticRequestBlockWeight +
                    record.thirdPartyCount * config.thirdPartyWeight +
                    record.redirectCorrelationCount * config.correlatedRedirectWeight
            AdaptiveCandidateType.FIRST_PARTY_LOADER ->
                record.staticBlockCount * config.staticRequestBlockWeight +
                    record.occurrenceCount * (config.exactLoaderPathWeight - config.firstPartyPenalty)
            AdaptiveCandidateType.DOM_STRUCTURE ->
                record.occurrenceCount * config.domObservationWeight
        }
        return (positive - record.functionalEvidenceCount * config.functionalEvidencePenalty).coerceAtLeast(0)
    }

    private fun confidence(score: Int, threshold: Int): Int =
        ((score.toLong() * 100L) / threshold.coerceAtLeast(1)).coerceIn(0, 100).toInt()

    private fun autoThreshold(type: AdaptiveCandidateType): Int = when (type) {
        AdaptiveCandidateType.OFFSITE_REDIRECT_HOST -> config.redirectAutoThreshold
        AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST -> config.thirdPartyAutoThreshold
        AdaptiveCandidateType.FIRST_PARTY_LOADER -> config.loaderAutoThreshold
        AdaptiveCandidateType.DOM_STRUCTURE -> Int.MAX_VALUE
    }

    private fun learnedCount(profileId: String): Int =
        records.values.count { it.profileId == profileId && it.state == AdaptiveCandidateState.LEARNED }

    private fun trimProfile(profileId: String) {
        val profileRecords = records.values.filter { it.profileId == profileId }
        val overflow = profileRecords.size - config.maxCandidatesPerProfile
        if (overflow <= 0) return
        profileRecords
            .filter { it.state != AdaptiveCandidateState.LEARNED }
            .sortedWith(compareBy<AdaptiveRecord> { it.confidence }.thenBy { it.lastSeenAtMs })
            .take(overflow)
            .forEach { records.remove(it.id) }
    }

    private fun AdaptiveRecord.withObservation(observation: AdaptiveObservation): AdaptiveRecord = when (observation) {
        is AdaptiveNavigationObservation -> copy(
            occurrenceCount = occurrenceCount + 1,
            popupCount = popupCount + if (observation.popup) 1 else 0,
            sourcePolicyBlockCount = sourcePolicyBlockCount + if (observation.blockedBySourcePolicy) 1 else 0,
            lastSeenAtMs = observation.observedAtMs,
        )
        is AdaptiveRequestObservation -> copy(
            occurrenceCount = occurrenceCount + 1,
            staticBlockCount = staticBlockCount + if (observation.blockedByStaticRule) 1 else 0,
            thirdPartyCount = thirdPartyCount + if (observation.thirdParty) 1 else 0,
            redirectCorrelationCount = redirectCorrelationCount + if (observation.correlatedWithRedirect) 1 else 0,
            functionalEvidenceCount = functionalEvidenceCount + if (observation.functionalEvidence) 1 else 0,
            lastSeenAtMs = observation.observedAtMs,
        )
        is AdaptiveDomObservation -> copy(
            occurrenceCount = occurrenceCount + 1,
            lastSeenAtMs = observation.observedAtMs,
        )
    }
}

object AdaptiveObservationFactory {
    fun navigation(
        profile: SiteProfile,
        sourceUrl: String?,
        targetUrl: String,
        popup: Boolean,
        blockedBySourcePolicy: Boolean,
        observedAtMs: Long,
    ): AdaptiveNavigationObservation? {
        if (!profile.adaptivePolicy.enabled || !profile.adaptivePolicy.observeOffsiteNavigations) return null
        val sourceHost = sourceUrl?.hostFromUrl()
        val targetHost = targetUrl.hostFromUrl() ?: return null
        if (sourceHost == targetHost || profile.allowedHosts.any { it.matches(targetHost) }) return null
        return AdaptiveNavigationObservation(
            profileId = profile.id,
            host = targetHost,
            pageType = profile.pageTypeFor(sourceUrl),
            observedAtMs = observedAtMs,
            popup = popup,
            blockedBySourcePolicy = blockedBySourcePolicy,
        )
    }

    fun request(
        profile: SiteProfile,
        pageUrl: String?,
        requestUrl: String,
        blockedByStaticRule: Boolean,
        correlatedWithRedirect: Boolean,
        functionalEvidence: Boolean,
        resourceKind: AdaptiveResourceKind,
        observedAtMs: Long,
    ): AdaptiveRequestObservation? {
        if (!profile.adaptivePolicy.enabled || !profile.adaptivePolicy.observeThirdPartyRequests) return null
        val parsed = requestUrl.toUriOrNull() ?: return null
        val host = parsed.host.normalizedHost() ?: return null
        val path = sanitizeAdaptivePath(parsed.path)
        val firstParty = profile.allowedHosts.any { it.matches(host) }
        val loaderPath = firstParty && profile.adaptivePolicy.firstPartyLoaderPathPrefixes.any { prefix ->
            path.startsWith(prefix.normalizedPath().orEmpty())
        }
        if (firstParty && !loaderPath) return null
        return AdaptiveRequestObservation(
            profileId = profile.id,
            host = host,
            path = path,
            pageType = profile.pageTypeFor(pageUrl),
            observedAtMs = observedAtMs,
            thirdParty = !firstParty,
            blockedByStaticRule = blockedByStaticRule,
            correlatedWithRedirect = correlatedWithRedirect,
            functionalEvidence = functionalEvidence,
            loaderPath = loaderPath,
            resourceKind = resourceKind,
        )
    }
}

fun sanitizeAdaptivePath(path: String?): String {
    val normalized = path.normalizedPath() ?: "/"
    return normalized
        .split('/')
        .joinToString("/") { segment ->
            when {
                segment.matches(Regex("\\d+")) -> "{numeric}"
                segment.length >= 16 && segment.matches(Regex("[a-f0-9_-]+", RegexOption.IGNORE_CASE)) -> "{id}"
                else -> segment.take(80)
            }
        }
        .take(240)
}

fun adaptiveResourceKind(url: String, headers: Map<String, String>): AdaptiveResourceKind {
    val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
        ?.value
        ?.lowercase(Locale.US)
        .orEmpty()
    val path = url.toUriOrNull()?.path?.lowercase(Locale.US).orEmpty()
    return when {
        accept.contains("image/") || path.matches(Regex(".*\\.(png|jpe?g|webp|gif|avif|svg)$")) ->
            AdaptiveResourceKind.IMAGE
        accept.contains("video/") || path.matches(Regex(".*\\.(mp4|webm|m3u8)$")) ->
            AdaptiveResourceKind.VIDEO
        accept.contains("font/") || path.matches(Regex(".*\\.(woff2?|ttf|otf)$")) ->
            AdaptiveResourceKind.FONT
        else -> AdaptiveResourceKind.OTHER
    }
}

private fun AdaptiveObservation.candidateType(): AdaptiveCandidateType = when (this) {
    is AdaptiveNavigationObservation -> AdaptiveCandidateType.OFFSITE_REDIRECT_HOST
    is AdaptiveRequestObservation ->
        if (loaderPath) AdaptiveCandidateType.FIRST_PARTY_LOADER else AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST
    is AdaptiveDomObservation -> AdaptiveCandidateType.DOM_STRUCTURE
}

private fun AdaptiveCandidateType.riskTier(): AdaptiveRiskTier = when (this) {
    AdaptiveCandidateType.OFFSITE_REDIRECT_HOST -> AdaptiveRiskTier.LOW_RISK
    AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST,
    AdaptiveCandidateType.FIRST_PARTY_LOADER,
    -> AdaptiveRiskTier.MEDIUM_RISK
    AdaptiveCandidateType.DOM_STRUCTURE -> AdaptiveRiskTier.HIGH_RISK
}

private fun adaptiveRuleId(
    profileId: String,
    type: AdaptiveCandidateType,
    host: String,
    path: String?,
): String = "adaptive:$profileId:${type.name.lowercase(Locale.US)}:$host:${path ?: "host"}"

private fun String.safeDomFingerprint(): String =
    lowercase(Locale.US)
        .filter { it.isLetterOrDigit() || it in setOf('-', '_', '.', '#') }
        .take(120)
        .ifBlank { "structure" }

private fun SiteProfile.pageTypeFor(url: String?): PageType =
    pageTypeRules.firstOrNull { url != null && it.matches(url) }?.pageType ?: PageType.UNKNOWN
