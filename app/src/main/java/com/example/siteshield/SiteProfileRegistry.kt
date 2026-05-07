package com.example.siteshield

object SiteProfileRegistry {
    val defaultProfile: SiteProfile = DefaultProfile.profile

    val supportedProfiles: List<SiteProfile> = listOf(
        MangakakalotProfile.profile,
    )

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
