package com.example.siteshield

class SiteProfileCatalog(
    val defaultProfile: SiteProfile,
    val supportedProfiles: List<SiteProfile>,
) {
    fun byId(id: String?): SiteProfile =
        supportedProfiles.firstOrNull { it.id == id } ?: defaultProfile

    fun match(urlOrHost: String?): SiteProfile {
        val host = urlOrHost.hostLikeValue()
        return supportedProfiles.firstOrNull { profile ->
            profile.allowedHosts.any { it.matches(host) }
        } ?: defaultProfile
    }

    fun selectableProfiles(): List<SiteProfile> = supportedProfiles

    private fun String?.hostLikeValue(): String? {
        if (this.isNullOrBlank()) return null
        return if (contains("://")) hostFromUrl() else normalizedHost()
    }
}

object SiteProfileRegistry {
    internal val catalog = SiteProfileCatalog(
        defaultProfile = DefaultProfile.profile,
        supportedProfiles = listOf(
            MangakakalotProfile.profile,
            PalworldGgProfile.profile,
            AquaReaderProfile.profile,
            YouTubeProfile.profile,
            FacebookProfile.profile,
        ),
    )

    val defaultProfile: SiteProfile
        get() = catalog.defaultProfile

    val supportedProfiles: List<SiteProfile>
        get() = catalog.supportedProfiles

    fun byId(id: String?): SiteProfile = catalog.byId(id)

    fun match(urlOrHost: String?): SiteProfile = catalog.match(urlOrHost)

    fun selectableProfiles(): List<SiteProfile> = catalog.selectableProfiles()
}
