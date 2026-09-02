package com.example.siteshield

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class AdaptiveProtocolEvidence(
    val placementCount: Int = 0,
    val auctionCount: Int = 0,
    val impressionCount: Int = 0,
    val creativeCount: Int = 0,
    val clickCount: Int = 0,
    val popupCount: Int = 0,
    val bidderCount: Int = 0,
    val clusterCount: Int = 0,
) {
    val total: Int
        get() = placementCount + auctionCount + impressionCount + creativeCount + clickCount +
            popupCount + bidderCount + clusterCount

    val categories: Set<AdaptiveProtocolCategory>
        get() = buildSet {
            if (placementCount > 0) add(AdaptiveProtocolCategory.PLACEMENT)
            if (auctionCount > 0) add(AdaptiveProtocolCategory.AUCTION)
            if (impressionCount > 0) add(AdaptiveProtocolCategory.IMPRESSION)
            if (creativeCount > 0) add(AdaptiveProtocolCategory.CREATIVE)
            if (clickCount > 0) add(AdaptiveProtocolCategory.CLICK)
            if (popupCount > 0) add(AdaptiveProtocolCategory.POPUP)
            if (bidderCount > 0) add(AdaptiveProtocolCategory.BIDDER)
        }

    fun withCluster(count: Int): AdaptiveProtocolEvidence = copy(clusterCount = count.coerceAtLeast(0))
}

enum class AdaptiveProtocolCategory { PLACEMENT, AUCTION, IMPRESSION, CREATIVE, CLICK, POPUP, BIDDER }

data class AdaptiveProtocolClassification(
    val evidence: AdaptiveProtocolEvidence,
    val normalizedPath: String,
    val pathScoped: Boolean,
    val pathCategories: Set<AdaptiveProtocolCategory> = emptySet(),
    val queryCategories: Set<AdaptiveProtocolCategory> = emptySet(),
)

data class AdaptiveHostAdEvidence(
    val adLabelCount: Int = 0,
    val loaderRoleCount: Int = 0,
    val bidderRoleCount: Int = 0,
    val auctionRoleCount: Int = 0,
    val impressionRoleCount: Int = 0,
    val clickRoleCount: Int = 0,
    val popupRoleCount: Int = 0,
    val exchangeRoleCount: Int = 0,
) {
    val total: Int
        get() = adLabelCount + loaderRoleCount + bidderRoleCount + auctionRoleCount +
            impressionRoleCount + clickRoleCount + popupRoleCount + exchangeRoleCount

    fun diagnosticRoles(): String = buildList {
        if (adLabelCount > 0) add("ad-label")
        if (loaderRoleCount > 0) add("loader")
        if (bidderRoleCount > 0) add("bidder")
        if (auctionRoleCount > 0) add("auction")
        if (impressionRoleCount > 0) add("impression")
        if (clickRoleCount > 0) add("click")
        if (popupRoleCount > 0) add("popup")
        if (exchangeRoleCount > 0) add("exchange")
    }.joinToString("+").ifBlank { "none" }
}

data class AdaptiveHostAdClassification(
    val evidence: AdaptiveHostAdEvidence,
    val strongSeed: Boolean,
    val joinEligible: Boolean,
)

/** Exact hostname-label classifier. It never substring-matches arbitrary words. */
object AdaptiveHostAdClassifier {
    private val strongAdLabels = setOf("ads", "advert", "adserver", "adservice", "adx", "pubadx")
    private val bidderLabels = setOf("bid", "bidder", "prebid")
    private val auctionLabels = setOf("auction", "rtb")
    private val impressionLabels = setOf("imp", "impression")
    private val exchangeLabels = setOf("ssp", "dsp", "exchange")
    private val clickLabels = setOf("click", "onclick", "onclck", "clk")
    private val popupLabels = setOf("pop", "popup", "popunder")

    fun classify(host: String, resourceKind: AdaptiveResourceKind): AdaptiveHostAdClassification {
        val normalized = host.normalizedHost().orEmpty()
        val dotLabels = normalized.split('.').filter(String::isNotBlank)
        val components = dotLabels.flatMap { it.split('-', '_') }.filter(String::isNotBlank).toSet()
        val strongAd = components.any { it in strongAdLabels }
        val weakAd = "ad" in components && !strongAd
        val bidder = components.any { it in bidderLabels }
        val auction = components.any { it in auctionLabels }
        val impression = components.any { it in impressionLabels }
        val exchange = components.any { it in exchangeLabels } || components.any { it in setOf("adx", "pubadx") }
        val click = components.any { it in clickLabels } || dotLabels.any { label ->
            label.startsWith("onclck") && label.removePrefix("onclck").matches(Regex("[a-z0-9]{1,12}"))
        }
        val popup = components.any { it in popupLabels }
        val adLabel = when { strongAd -> 2; weakAd -> 1; else -> 0 }
        val roleEvidence = adLabel > 0 || bidder || auction || impression || exchange || click || popup
        val scriptLoader = resourceKind == AdaptiveResourceKind.SCRIPT && roleEvidence
        val evidence = AdaptiveHostAdEvidence(
            adLabelCount = adLabel,
            loaderRoleCount = if (scriptLoader) 1 else 0,
            bidderRoleCount = if (bidder) 1 else 0,
            auctionRoleCount = if (auction) 1 else 0,
            impressionRoleCount = if (impression) 1 else 0,
            clickRoleCount = if (click) 1 else 0,
            popupRoleCount = if (popup) 1 else 0,
            exchangeRoleCount = if (exchange) 1 else 0,
        )
        val strongSeed = strongAd || bidder || auction || exchange || click || popup ||
            (resourceKind == AdaptiveResourceKind.SCRIPT && weakAd)
        return AdaptiveHostAdClassification(evidence, strongSeed, roleEvidence)
    }
}

/** Pure, identifier-free classifier. Raw values are inspected only for a small predefined category set. */
object AdaptiveProtocolClassifier {
    private val parameterCategories = mapOf(
        "zoneid" to AdaptiveProtocolCategory.PLACEMENT,
        "zone_id" to AdaptiveProtocolCategory.PLACEMENT,
        "siteid" to AdaptiveProtocolCategory.PLACEMENT,
        "site_id" to AdaptiveProtocolCategory.PLACEMENT,
        "placement" to AdaptiveProtocolCategory.PLACEMENT,
        "placement_id" to AdaptiveProtocolCategory.PLACEMENT,
        "spot_id" to AdaptiveProtocolCategory.PLACEMENT,
        "slot" to AdaptiveProtocolCategory.PLACEMENT,
        "adunit" to AdaptiveProtocolCategory.PLACEMENT,
        "ad_unit" to AdaptiveProtocolCategory.PLACEMENT,
        "auction" to AdaptiveProtocolCategory.AUCTION,
        "auction_id" to AdaptiveProtocolCategory.AUCTION,
        "bid" to AdaptiveProtocolCategory.AUCTION,
        "bid_id" to AdaptiveProtocolCategory.AUCTION,
        "ssp" to AdaptiveProtocolCategory.BIDDER,
        "dsp" to AdaptiveProtocolCategory.BIDDER,
        "bidder" to AdaptiveProtocolCategory.BIDDER,
        "imp" to AdaptiveProtocolCategory.IMPRESSION,
        "impression" to AdaptiveProtocolCategory.IMPRESSION,
        "impression_id" to AdaptiveProtocolCategory.IMPRESSION,
        "creative" to AdaptiveProtocolCategory.CREATIVE,
        "creative_id" to AdaptiveProtocolCategory.CREATIVE,
        "click_id" to AdaptiveProtocolCategory.CLICK,
        "clickid" to AdaptiveProtocolCategory.CLICK,
    )
    private val categoricalParameters = setOf("resp_type", "response_type", "campaign_type", "ad_type", "ad_format")
    private val popupValues = setOf("pop", "popup", "popunder", "popunderad", "lq-pop")
    private val auctionValues = setOf("auction", "bid", "rtb", "openrtb")
    private val pathCategories = mapOf(
        "auction" to AdaptiveProtocolCategory.AUCTION,
        "bid" to AdaptiveProtocolCategory.AUCTION,
        "bids" to AdaptiveProtocolCategory.AUCTION,
        "impression" to AdaptiveProtocolCategory.IMPRESSION,
        "imp" to AdaptiveProtocolCategory.IMPRESSION,
        "creative" to AdaptiveProtocolCategory.CREATIVE,
        "click" to AdaptiveProtocolCategory.CLICK,
        "popup" to AdaptiveProtocolCategory.POPUP,
        "pop" to AdaptiveProtocolCategory.POPUP,
        "popunder" to AdaptiveProtocolCategory.POPUP,
        "adserver" to AdaptiveProtocolCategory.PLACEMENT,
    )

    fun classify(url: String, resourceKind: AdaptiveResourceKind): AdaptiveProtocolClassification? {
        if (resourceKind in setOf(AdaptiveResourceKind.IMAGE, AdaptiveResourceKind.VIDEO, AdaptiveResourceKind.FONT)) {
            return null
        }
        val uri = url.toUriOrNull() ?: return null
        val normalizedPath = sanitizeAdaptivePath(uri.path)
        val pathEvidence = linkedSetOf<AdaptiveProtocolCategory>()
        val queryEvidence = linkedSetOf<AdaptiveProtocolCategory>()
        val pathTokens = normalizedPath.lowercase(Locale.US).split(Regex("[^a-z0-9]+"))
        pathTokens.mapNotNullTo(pathEvidence) { pathCategories[it] }
        uri.rawQuery.orEmpty().split('&').take(MAX_QUERY_PARAMETERS).forEach { pair ->
            val separator = pair.indexOf('=')
            val rawName = if (separator >= 0) pair.substring(0, separator) else pair
            val name = decode(rawName).lowercase(Locale.US).take(MAX_TOKEN_LENGTH)
            parameterCategories[name]?.let(queryEvidence::add)
            if (name in categoricalParameters && separator >= 0) {
                val value = decode(pair.substring(separator + 1)).lowercase(Locale.US).take(MAX_TOKEN_LENGTH)
                if (value in popupValues) queryEvidence += AdaptiveProtocolCategory.POPUP
                if (value in auctionValues) queryEvidence += AdaptiveProtocolCategory.AUCTION
            }
        }
        val categories = pathEvidence + queryEvidence
        if (categories.isEmpty()) return null
        val evidence = AdaptiveProtocolEvidence(
            placementCount = AdaptiveProtocolCategory.PLACEMENT.presentIn(categories),
            auctionCount = AdaptiveProtocolCategory.AUCTION.presentIn(categories),
            impressionCount = AdaptiveProtocolCategory.IMPRESSION.presentIn(categories),
            creativeCount = AdaptiveProtocolCategory.CREATIVE.presentIn(categories),
            clickCount = AdaptiveProtocolCategory.CLICK.presentIn(categories),
            popupCount = AdaptiveProtocolCategory.POPUP.presentIn(categories),
            bidderCount = AdaptiveProtocolCategory.BIDDER.presentIn(categories),
        )
        return AdaptiveProtocolClassification(
            evidence = evidence,
            normalizedPath = normalizedPath,
            pathScoped = pathTokens.any { it in pathCategories },
            pathCategories = pathEvidence,
            queryCategories = queryEvidence,
        )
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault("")

    private fun AdaptiveProtocolCategory.presentIn(categories: Set<AdaptiveProtocolCategory>): Int =
        if (this in categories) 1 else 0

    private const val MAX_QUERY_PARAMETERS = 48
    private const val MAX_TOKEN_LENGTH = 48
}

class AdaptiveSeededAdCluster(
    private val windowMs: Long = 8_000L,
    private val maxEvents: Int = 64,
) {
    data class Event(
        val scope: AdaptiveScope,
        val documentKey: String,
        val host: String,
        val path: String,
        val resourceKind: AdaptiveResourceKind,
        val hostEvidence: AdaptiveHostAdEvidence,
        val protocolEvidence: AdaptiveProtocolEvidence,
        val strongSeed: Boolean,
        val joinEligible: Boolean,
        val pathScoped: Boolean,
        val functionalConflict: Boolean,
        val observedAtMs: Long,
    )

    data class Result(
        val clusterSeed: Boolean,
        val joined: Boolean,
        val episodeCredits: List<Event>,
    )

    private val events = ArrayDeque<Event>()
    private val credited = linkedSetOf<String>()

    @Synchronized
    fun observe(event: Event): Result {
        events.removeIf { event.observedAtMs - it.observedAtMs > windowMs }
        val liveDocuments = events.mapTo(hashSetOf()) { "${it.scope.diagnosticName}|${it.documentKey}" }
        credited.removeIf { key -> liveDocuments.none { prefix -> key.startsWith("$prefix|") } }
        while (events.size >= maxEvents) events.removeFirst()
        if (event.strongSeed || event.joinEligible) events.addLast(event)
        val related = events.filter { it.scope == event.scope && it.documentKey == event.documentKey }
        val hasSeed = related.any(Event::strongSeed)
        val participants = related.filter { it.strongSeed || it.joinEligible }
        val crossHost = participants.map(Event::host).distinct().size >= 2
        val credits = if (hasSeed && crossHost) participants.filter { participant ->
            val key = creditKey(participant)
            if (key in credited) false else credited.add(key)
        } else {
            emptyList()
        }
        return Result(event.strongSeed, hasSeed && crossHost && (event.strongSeed || event.joinEligible), credits)
    }

    @Synchronized fun size(): Int = events.size

    @Synchronized
    fun hasActiveSeed(scope: AdaptiveScope, documentKey: String, nowMs: Long): Boolean {
        events.removeIf { nowMs - it.observedAtMs > windowMs }
        return events.any { it.scope == scope && it.documentKey == documentKey && it.strongSeed }
    }

    private fun creditKey(event: Event): String =
        "${event.scope.diagnosticName}|${event.documentKey}|${event.host}|" +
            if (event.pathScoped) event.path else "host"
}
