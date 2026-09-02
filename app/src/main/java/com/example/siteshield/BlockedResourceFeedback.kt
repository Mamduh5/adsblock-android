package com.example.siteshield

data class BlockedResourceEvidence(
    val siteScope: String,
    val host: String,
    val path: String,
    val resourceKind: AdaptiveResourceKind,
    val blockedAtMs: Long,
) {
    companion object {
        fun from(pageUrl: String?, resourceUrl: String, resourceKind: AdaptiveResourceKind, blockedAtMs: Long):
            BlockedResourceEvidence? {
            val siteScope = pageUrl?.hostFromUrl() ?: return null
            val uri = resourceUrl.toUriOrNull() ?: return null
            val host = uri.host.normalizedHost() ?: return null
            return BlockedResourceEvidence(siteScope, host, sanitizeAdaptivePath(uri.path), resourceKind, blockedAtMs)
        }
    }

    fun toJavascriptObject(): String =
        "{\"host\":\"${host.javascriptString()}\",\"path\":\"${path.javascriptString()}\"," +
            "\"kind\":\"${resourceKind.name}\"}"
}

private fun String.javascriptString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
