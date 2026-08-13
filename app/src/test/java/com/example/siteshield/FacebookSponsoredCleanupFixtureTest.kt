package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookSponsoredCleanupFixtureTest {
    private val rule = FacebookProfile.profile.baselinePolicy.domRules.ancestorCleanupRules
        .single { it.removalReason == "facebook-sponsored-feed" }

    @Test
    fun `sponsored fixture collapses payload but preserves Facebook vscroller owner`() {
        val fixture = sponsoredFixture()

        val resolution = resolveFixture(rule, fixture)

        assertEquals(4, resolution?.rootDepth)
        assertEquals("facebook-sponsored-feed", resolution?.reason)
        assertEquals(
            AncestorNeutralizationStrategy.PRESERVE_ANCESTOR_HIDE_CHILDREN,
            resolution?.strategy,
        )
        assertTrue(resolution?.rootPreserved == true)
        assertTrue(resolution?.directPayloadChildrenHidden == true)
    }

    @Test
    fun `ordinary suggested and similar-text fixtures remain visible`() {
        assertNull(resolveFixture(rule, ordinaryFixture("Ordinary fixture")))
        assertNull(resolveFixture(rule, ordinaryFixture("Suggested for you")))
        assertNull(
            resolveFixture(
                rule,
                ordinaryFixture(
                    text = rule.markerTextPrefixes.single() + " discussed in ordinary body copy",
                    markerSelector = "[data-mcomponent='ServerTextArea'][data-type='text']",
                ),
            ),
        )
    }

    @Test
    fun `partially constructed item waits for the confirmed vscroller boundary`() {
        val partial = sponsoredFixture().dropLast(1)

        assertNull(resolveFixture(rule, partial))
    }

    @Test
    fun `recycled owner is released when sponsored marker is replaced`() {
        val fixture = sponsoredFixture().toMutableList()
        assertTrue(ownerStillMatches(rule, fixture))

        fixture[0] = fixture[0].copy(text = "Ordinary recycled fixture")

        assertFalse(ownerStillMatches(rule, fixture))
    }

    private fun sponsoredFixture(): List<FixtureNode> = listOf(
        FixtureNode(rule.markerSelector, rule.markerTextPrefixes.single() + " synthetic suffix"),
        FixtureNode("header-level-1"),
        FixtureNode("header-level-2"),
        FixtureNode("payload-wrapper"),
        FixtureNode(rule.ancestorSelector),
        FixtureNode(rule.ancestorParentSelector),
    )

    private fun ordinaryFixture(
        text: String,
        markerSelector: String = rule.markerSelector,
    ): List<FixtureNode> = listOf(
        FixtureNode(markerSelector, text),
        FixtureNode("header-level-1"),
        FixtureNode("header-level-2"),
        FixtureNode("payload-wrapper"),
        FixtureNode(rule.ancestorSelector),
        FixtureNode(rule.ancestorParentSelector),
    )
}

private data class FixtureNode(
    val selector: String,
    val text: String = "",
)

private data class FixtureResolution(
    val rootDepth: Int,
    val reason: String,
    val strategy: AncestorNeutralizationStrategy,
) {
    val rootPreserved: Boolean
        get() = strategy == AncestorNeutralizationStrategy.PRESERVE_ANCESTOR_HIDE_CHILDREN

    val directPayloadChildrenHidden: Boolean
        get() = rootPreserved
}

private fun resolveFixture(
    rule: AncestorDomCleanupRule,
    markerToParentPath: List<FixtureNode>,
): FixtureResolution? {
    val marker = markerToParentPath.firstOrNull() ?: return null
    if (marker.selector != rule.markerSelector || !rule.matchesMarkerText(marker.text)) return null

    for (depth in 0..rule.maxAncestorDepth) {
        val candidate = markerToParentPath.getOrNull(depth) ?: return null
        val parent = markerToParentPath.getOrNull(depth + 1) ?: return null
        if (candidate.selector == rule.ancestorSelector && parent.selector == rule.ancestorParentSelector) {
            return FixtureResolution(depth, rule.removalReason, rule.neutralizationStrategy)
        }
    }
    return null
}

private fun ownerStillMatches(
    rule: AncestorDomCleanupRule,
    markerToParentPath: List<FixtureNode>,
): Boolean = resolveFixture(rule, markerToParentPath) != null
