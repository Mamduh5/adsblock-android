package com.example.siteshield

import java.util.Locale

private const val DOM_AD_REPORT_VERSION = "A4"
private const val DOM_AD_REPORT_MAX = 32
private const val DOM_AD_SLOT_ID_MAX = 4_096

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
    val slotId: Int = 1,
) {
    fun evidence(): AdaptiveAdEvidence = AdaptiveAdEvidence(
        explicitAdSlotCount = if (explicitAdSlot) 1 else 0,
        sponsoredAttributionCount = if (sponsoredAttribution) 1 else 0,
        adIframeCorrelationCount = if (iframeAssociation) 1 else 0,
        overlayAdCount = if (overlayLayout) 1 else 0,
        repeatedLoaderCorrelationCount = if (role == AdaptiveAdResourceRole.LOADER) 1 else 0,
    )
}

data class AdaptiveDomAdDrain(
    val reports: List<AdaptiveDomAdReport>,
    val observerInstalled: Boolean,
    val overflowCount: Int,
    val pendingCount: Int,
)

/** Parses only the compact, query-free records returned by the bundled observer asset. */
fun parseAdaptiveDomAdReports(serialized: String?): List<AdaptiveDomAdReport> {
    return parseAdaptiveDomAdDrain(serialized).reports
}

fun parseAdaptiveDomAdDrain(serialized: String?): AdaptiveDomAdDrain {
    if (serialized.isNullOrBlank()) return AdaptiveDomAdDrain(emptyList(), false, 0, 0)
    val lines = serialized.lineSequence().toList()
    val metadata = lines.firstOrNull()?.split('\t').orEmpty()
    val hasMetadata = metadata.size == 4 && metadata[0] == "M4"
    val installed = hasMetadata && metadata[1] == "1"
    val overflow = if (hasMetadata) metadata[2].toIntOrNull()?.coerceIn(0, 10_000) ?: 0 else 0
    val pending = if (hasMetadata) metadata[3].toIntOrNull()?.coerceIn(0, 128) ?: 0 else 0
    val reports = lines.asSequence()
        .drop(if (hasMetadata) 1 else 0)
        .take(DOM_AD_REPORT_MAX)
        .mapNotNull(::parseAdaptiveDomAdReport)
        .distinct()
        .toList()
    return AdaptiveDomAdDrain(reports, installed, overflow, pending)
}

private fun parseAdaptiveDomAdReport(line: String): AdaptiveDomAdReport? = runCatching {
    val fields = line.split('\t')
    if (fields.size != 8 || fields[0] != DOM_AD_REPORT_VERSION) return null
    val flags = fields[1].toInt().takeIf { it in 1..15 } ?: return null
    val role = AdaptiveAdResourceRole.valueOf(fields[2].uppercase(Locale.US))
    val slotId = fields[3].toIntOrNull()?.takeIf { it in 1..DOM_AD_SLOT_ID_MAX } ?: return null
    val host = fields[4].normalizedHost() ?: return null
    val path = sanitizeAdaptivePath(fields[5])
    val pathScoped = fields[6].toBooleanStrict()
    val structuralContext = fields[7].toBooleanStrict()
    val explicit = flags and 1 != 0
    val sponsored = flags and 2 != 0
    val iframe = flags and 4 != 0
    val overlay = flags and 8 != 0
    if (!structuralContext || (!explicit && !sponsored)) return null
    if (role == AdaptiveAdResourceRole.IFRAME && !iframe) return null
    if (overlay && !explicit && !sponsored && !iframe) return null
    AdaptiveDomAdReport(role, host, path, explicit, sponsored, iframe, overlay, pathScoped, slotId)
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
    val slotId: Int = 1,
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
                        slotId = facts.slotId,
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
                facts.slotId,
            )?.let(::add)
            reportFor(
                AdaptiveAdResourceRole.LOADER,
                facts.loaderUrl,
                facts.explicitSlotMarker,
                sponsored,
                iframe = false,
                overlay,
                facts.slotId,
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
        slotId: Int,
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
            slotId = slotId,
        )
    }
}
