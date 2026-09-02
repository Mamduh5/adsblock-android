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
)

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
        val categories = linkedSetOf<AdaptiveProtocolCategory>()
        val pathTokens = normalizedPath.lowercase(Locale.US).split(Regex("[^a-z0-9]+"))
        pathTokens.mapNotNullTo(categories) { pathCategories[it] }
        uri.rawQuery.orEmpty().split('&').take(MAX_QUERY_PARAMETERS).forEach { pair ->
            val separator = pair.indexOf('=')
            val rawName = if (separator >= 0) pair.substring(0, separator) else pair
            val name = decode(rawName).lowercase(Locale.US).take(MAX_TOKEN_LENGTH)
            parameterCategories[name]?.let(categories::add)
            if (name in categoricalParameters && separator >= 0) {
                val value = decode(pair.substring(separator + 1)).lowercase(Locale.US).take(MAX_TOKEN_LENGTH)
                if (value in popupValues) categories += AdaptiveProtocolCategory.POPUP
                if (value in auctionValues) categories += AdaptiveProtocolCategory.AUCTION
            }
        }
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

class AdaptiveRequestCluster(
    private val windowMs: Long = 10_000L,
    private val maxEvents: Int = 64,
) {
    data class Event(
        val scope: AdaptiveScope,
        val host: String,
        val categories: Set<AdaptiveProtocolCategory>,
        val observedAtMs: Long,
    )

    private val events = ArrayDeque<Event>()

    @Synchronized
    fun observe(event: Event): Int {
        events.removeIf { event.observedAtMs - it.observedAtMs > windowMs }
        while (events.size >= maxEvents) events.removeFirst()
        events.addLast(event)
        val related = events.filter { it.scope == event.scope && it.host == event.host }
        val categories = related.flatMapTo(linkedSetOf()) { it.categories }
        val hasEntry = AdaptiveProtocolCategory.PLACEMENT in categories
        val hasExchange = AdaptiveProtocolCategory.AUCTION in categories || AdaptiveProtocolCategory.BIDDER in categories
        val hasOutcome = categories.any {
            it in setOf(AdaptiveProtocolCategory.IMPRESSION, AdaptiveProtocolCategory.CREATIVE,
                AdaptiveProtocolCategory.CLICK, AdaptiveProtocolCategory.POPUP)
        }
        return if (related.size >= 3 && hasEntry && hasExchange && hasOutcome) 1 else 0
    }

    @Synchronized fun size(): Int = events.size
}
