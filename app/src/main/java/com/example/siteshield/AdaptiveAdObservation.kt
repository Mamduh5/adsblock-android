package com.example.siteshield

import java.util.Locale

private const val DOM_AD_REPORT_VERSION = "A3"
private const val DOM_AD_REPORT_MAX = 32

enum class AdaptiveAdResourceRole {
    STRUCTURE,
    IFRAME,
    LOADER,
}

data class AdaptiveDomAdReport(
    val role: AdaptiveAdResourceRole,
    val host: String,
    val path: String,
    val explicitAdSlot: Boolean,
    val sponsoredAttribution: Boolean,
    val iframeAssociation: Boolean,
    val overlayLayout: Boolean,
    val pathScoped: Boolean,
) {
    fun evidence(): AdaptiveAdEvidence = AdaptiveAdEvidence(
        explicitAdSlotCount = if (explicitAdSlot) 1 else 0,
        sponsoredAttributionCount = if (sponsoredAttribution) 1 else 0,
        adIframeCorrelationCount = if (iframeAssociation) 1 else 0,
        overlayAdCount = if (overlayLayout) 1 else 0,
        repeatedLoaderCorrelationCount = if (role == AdaptiveAdResourceRole.LOADER) 1 else 0,
    )
}

/** Parses only the compact, query-free records returned by the bundled observer asset. */
fun parseAdaptiveDomAdReports(serialized: String?): List<AdaptiveDomAdReport> {
    if (serialized.isNullOrBlank()) return emptyList()
    return serialized.lineSequence()
        .take(DOM_AD_REPORT_MAX)
        .mapNotNull(::parseAdaptiveDomAdReport)
        .distinct()
        .toList()
}

private fun parseAdaptiveDomAdReport(line: String): AdaptiveDomAdReport? = runCatching {
    val fields = line.split('\t')
    if (fields.size != 7 || fields[0] != DOM_AD_REPORT_VERSION) return null
    val flags = fields[1].toInt().takeIf { it in 1..15 } ?: return null
    val role = AdaptiveAdResourceRole.valueOf(fields[2].uppercase(Locale.US))
    val host = fields[3].normalizedHost() ?: return null
    val path = sanitizeAdaptivePath(fields[4])
    val pathScoped = fields[5].toBooleanStrict()
    val structuralContext = fields[6].toBooleanStrict()
    val explicit = flags and 1 != 0
    val sponsored = flags and 2 != 0
    val iframe = flags and 4 != 0
    val overlay = flags and 8 != 0
    if (!structuralContext || (!explicit && !sponsored)) return null
    if (role == AdaptiveAdResourceRole.IFRAME && !iframe) return null
    if (overlay && !explicit && !sponsored && !iframe) return null
    AdaptiveDomAdReport(role, host, path, explicit, sponsored, iframe, overlay, pathScoped)
}.getOrNull()

data class AdaptiveDomNodeFacts(
    val explicitSlotMarker: Boolean = false,
    val shortAttribution: String? = null,
    val structuralAdContext: Boolean = false,
    val iframeUrl: String? = null,
    val loaderUrl: String? = null,
    val fixedOrSticky: Boolean = false,
    val viewportCoverage: Double = 0.0,
    val highZIndex: Boolean = false,
)

/** Pure mirror of the asset's conservative evidence gates, used for deterministic policy tests. */
object AdaptiveDomAdClassifier {
    fun classify(facts: AdaptiveDomNodeFacts): List<AdaptiveDomAdReport> {
        val sponsored = facts.shortAttribution?.trim()?.lowercase(Locale.US) in
            setOf("ad", "advertisement", "sponsored") && facts.structuralAdContext
        val hasAdIdentity = facts.explicitSlotMarker || sponsored
        if (!hasAdIdentity) return emptyList()
        val overlay = facts.fixedOrSticky && facts.highZIndex && facts.viewportCoverage >= 0.18
        return buildList {
            if (facts.iframeUrl == null && facts.loaderUrl == null) {
                add(
                    AdaptiveDomAdReport(
                        role = AdaptiveAdResourceRole.STRUCTURE,
                        host = "structure.invalid",
                        path = "/",
                        explicitAdSlot = facts.explicitSlotMarker,
                        sponsoredAttribution = sponsored,
                        iframeAssociation = false,
                        overlayLayout = overlay,
                        pathScoped = false,
                    ),
                )
            }
            reportFor(
                AdaptiveAdResourceRole.IFRAME,
                facts.iframeUrl,
                facts.explicitSlotMarker,
                sponsored,
                iframe = true,
                overlay,
            )?.let(::add)
            reportFor(
                AdaptiveAdResourceRole.LOADER,
                facts.loaderUrl,
                facts.explicitSlotMarker,
                sponsored,
                iframe = false,
                overlay,
            )?.let(::add)
        }
    }

    private fun reportFor(
        role: AdaptiveAdResourceRole,
        url: String?,
        explicit: Boolean,
        sponsored: Boolean,
        iframe: Boolean,
        overlay: Boolean,
    ): AdaptiveDomAdReport? {
        val parsed = url?.toUriOrNull() ?: return null
        val host = parsed.host.normalizedHost() ?: return null
        val path = sanitizeAdaptivePath(parsed.path)
        return AdaptiveDomAdReport(
            role = role,
            host = host,
            path = path,
            explicitAdSlot = explicit,
            sponsoredAttribution = sponsored,
            iframeAssociation = iframe,
            overlayLayout = overlay,
            pathScoped = role == AdaptiveAdResourceRole.LOADER || path != "/",
        )
    }
}
