package com.example.siteshield

import java.util.concurrent.atomic.AtomicReference

data class TopLevelContext(
    val url: String?,
    val profile: SiteProfile,
)

class TopLevelContextStore(initialContext: TopLevelContext) {
    private val context = AtomicReference(initialContext)

    fun snapshot(): TopLevelContext = context.get()

    fun update(updatedContext: TopLevelContext) {
        context.set(updatedContext)
    }
}

internal fun isNewTopLevelHistoryUrl(previousUrl: String?, updatedUrl: String?): Boolean =
    !updatedUrl.isNullOrBlank() && updatedUrl != previousUrl
