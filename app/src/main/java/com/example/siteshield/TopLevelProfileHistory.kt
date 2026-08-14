package com.example.siteshield

/** Session-only ownership records used solely to restore profile context on Back/Forward. */
class TopLevelProfileHistory(private val capacity: Int = 128) {
    private val profilesByUrl = LinkedHashMap<String, SiteProfile>(capacity, 0.75f, true)

    @Synchronized
    fun remember(url: String?, profile: SiteProfile) {
        if (url.isNullOrBlank()) return
        profilesByUrl[url] = profile
        while (profilesByUrl.size > capacity) {
            profilesByUrl.remove(profilesByUrl.entries.first().key)
        }
    }

    @Synchronized
    fun profileFor(url: String?): SiteProfile? = url?.let(profilesByUrl::get)
}
