package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOwnershipTest {
    private val defaultProfile = profile("default", emptyList())
    private val siteA = profile(
        id = "site-a",
        allowedHosts = listOf(HostPattern.DomainSuffix("site-a.example")),
        requestRules = listOf(
            RequestRule(
                id = "site-a-blocks-site-b-widget",
                host = HostPattern.Exact("site-b.example"),
                path = PathPattern.Exact("/widget.js"),
            ),
        ),
    )
    private val siteB = profile(
        id = "site-b",
        allowedHosts = listOf(HostPattern.DomainSuffix("site-b.example")),
    )
    private val engine = GenericBlockerEngine(
        SiteProfileCatalog(
            defaultProfile = defaultProfile,
            supportedProfiles = listOf(siteA, siteB),
        ),
    )

    @Test
    fun `main frame navigation can select a registered destination profile`() {
        val selected = engine.profileForRequest(
            "https://site-b.example/chapter/1",
            siteA,
            ProfileRequestContext.MAIN_FRAME_NAVIGATION,
        )

        assertEquals("site-b", selected.id)
    }

    @Test
    fun `site b subresource remains governed by site a policy`() {
        val selected = engine.profileForRequest(
            "https://site-b.example/widget.js",
            siteA,
            ProfileRequestContext.SUBRESOURCE,
        )

        assertEquals("site-a", selected.id)
        assertEquals(
            BlockDecision.Block(BlockReason.REQUEST_RULE, "site-a-blocks-site-b-widget"),
            engine.resourceDecision(
                selected,
                "https://site-b.example/widget.js",
                "https://site-a.example/chapter/1",
            ),
        )
        assertEquals(
            BlockDecision.Allow,
            engine.resourceDecision(
                siteB,
                "https://site-b.example/widget.js",
                "https://site-b.example/chapter/1",
            ),
        )
    }

    @Test
    fun `site b iframe remains governed by site a policy`() {
        val selected = engine.profileForRequest(
            "https://site-b.example/widget.js",
            siteA,
            ProfileRequestContext.SUBFRAME_NAVIGATION,
        )

        assertEquals("site-a", selected.id)
        assertEquals(
            BlockDecision.Block(BlockReason.REQUEST_RULE, "site-a-blocks-site-b-widget"),
            engine.navigationDecision(
                selected,
                "https://site-b.example/widget.js",
                "https://site-a.example/chapter/1",
                isMainFrame = false,
            ),
        )
    }

    @Test
    fun `unknown cdn resource retains active top level profile`() {
        val selected = engine.profileForRequest(
            "https://cdn.unknown.example/assets/app.js",
            siteA,
            ProfileRequestContext.SUBRESOURCE,
        )

        assertEquals("site-a", selected.id)
    }

    @Test
    fun `mangakakalot remains selected for its top level and third party resources`() {
        val productionEngine = GenericBlockerEngine()
        val mangakakalot = MangakakalotProfile.profile

        assertEquals(
            "mangakakalot",
            productionEngine.profileForRequest(
                "https://www.mangakakalot.gg/chapter/example/chapter-1",
                DefaultProfile.profile,
                ProfileRequestContext.MAIN_FRAME_NAVIGATION,
            ).id,
        )
        assertEquals(
            "mangakakalot",
            productionEngine.profileForRequest(
                "https://d2dxy39sqorbhv.cloudfront.net/pixel?syxdd=1257018",
                mangakakalot,
                ProfileRequestContext.SUBRESOURCE,
            ).id,
        )
        assertEquals(
            BlockDecision.Block(BlockReason.REQUEST_RULE, "cloudfront-syxdd-1257018"),
            productionEngine.resourceDecision(
                mangakakalot,
                "https://d2dxy39sqorbhv.cloudfront.net/pixel?syxdd=1257018",
                "https://www.mangakakalot.gg/chapter/example/chapter-1",
            ),
        )
    }

    @Test
    fun `main frame navigation switches between production profiles`() {
        val productionEngine = GenericBlockerEngine()

        assertEquals(
            "palworld-gg",
            productionEngine.profileForRequest(
                "https://palworld.gg/map",
                MangakakalotProfile.profile,
                ProfileRequestContext.MAIN_FRAME_NAVIGATION,
            ).id,
        )
        assertEquals(
            "mangakakalot",
            productionEngine.profileForRequest(
                "https://www.mangakakalot.gg/",
                PalworldGgProfile.profile,
                ProfileRequestContext.MAIN_FRAME_NAVIGATION,
            ).id,
        )
    }

    @Test
    fun `palworld subresources retain palworld top level ownership`() {
        val productionEngine = GenericBlockerEngine()

        assertEquals(
            "palworld-gg",
            productionEngine.profileForRequest(
                "https://s.nitropay.com/ads-1813.js",
                PalworldGgProfile.profile,
                ProfileRequestContext.SUBRESOURCE,
            ).id,
        )
    }

    @Test
    fun `main frame navigation switches to and from aquareader`() {
        val productionEngine = GenericBlockerEngine()

        listOf(MangakakalotProfile.profile, PalworldGgProfile.profile).forEach { source ->
            assertEquals(
                "aquareader",
                productionEngine.profileForRequest(
                    "https://aquareader.org/manga/hello-mr-veterinarian/",
                    source,
                    ProfileRequestContext.MAIN_FRAME_NAVIGATION,
                ).id,
            )
        }

        listOf(
            "https://www.mangakakalot.gg/" to "mangakakalot",
            "https://palworld.gg/" to "palworld-gg",
        ).forEach { (url, expected) ->
            assertEquals(
                expected,
                productionEngine.profileForRequest(
                    url,
                    AquaReaderProfile.profile,
                    ProfileRequestContext.MAIN_FRAME_NAVIGATION,
                ).id,
            )
        }
    }

    @Test
    fun `aquareader subresources retain aquareader top level ownership`() {
        val selected = GenericBlockerEngine().profileForRequest(
            "https://cdn.example.org/chapter/page-001.webp",
            AquaReaderProfile.profile,
            ProfileRequestContext.SUBRESOURCE,
        )

        assertEquals("aquareader", selected.id)
    }

    @Test
    fun `main frame navigation switches to and from youtube`() {
        val productionEngine = GenericBlockerEngine()

        listOf(
            MangakakalotProfile.profile,
            PalworldGgProfile.profile,
            AquaReaderProfile.profile,
        ).forEach { source ->
            assertEquals(
                "youtube",
                productionEngine.profileForRequest(
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    source,
                    ProfileRequestContext.MAIN_FRAME_NAVIGATION,
                ).id,
            )
        }

        listOf(
            "https://www.mangakakalot.gg/" to "mangakakalot",
            "https://palworld.gg/" to "palworld-gg",
            "https://aquareader.org/" to "aquareader",
        ).forEach { (url, expected) ->
            assertEquals(
                expected,
                productionEngine.profileForRequest(
                    url,
                    YouTubeProfile.profile,
                    ProfileRequestContext.MAIN_FRAME_NAVIGATION,
                ).id,
            )
        }
    }

    @Test
    fun `youtube media subresources retain youtube top level ownership`() {
        val selected = GenericBlockerEngine().profileForRequest(
            "https://rr1---sn.example.googlevideo.com/videoplayback?id=content",
            YouTubeProfile.profile,
            ProfileRequestContext.SUBRESOURCE,
        )

        assertEquals("youtube", selected.id)
    }

    private fun profile(
        id: String,
        allowedHosts: List<HostPattern>,
        requestRules: List<RequestRule> = emptyList(),
    ): SiteProfile =
        SiteProfile(
            id = id,
            displayName = id,
            startUrl = "https://$id.example/",
            allowedHosts = allowedHosts,
            baselinePolicy = PagePolicy(requestRules = requestRules),
        )
}
