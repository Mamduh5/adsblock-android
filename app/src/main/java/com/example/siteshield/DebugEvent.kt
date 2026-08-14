package com.example.siteshield

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DebugEventCategory {
    PROFILE,
    PAGE_TYPE,
    NAV_BLOCK,
    RESOURCE_BLOCK,
    DOM_CLEANUP,
    STORAGE_CLEANUP,
    COOKIE_CLEANUP,
    POLICY_DECISION,
    DATA_SAVER,
    DATA_SAVER_BLOCK,
    DOWNLOAD,
}

data class DebugEvent(
    val category: DebugEventCategory,
    val message: String,
    val detail: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun formatted(maxMessageLength: Int = DEFAULT_MESSAGE_LIMIT): String {
        val time = TimeFormatter.format(Date(timestampMs))
        val body = listOfNotNull(message, detail?.takeIf { it.isNotBlank() })
            .joinToString(" | ")
            .limitForDebug(maxMessageLength)
        return "$time ${category.name}: $body"
    }

    companion object {
        const val DEFAULT_MESSAGE_LIMIT = 320
        private val TimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

class DebugEventLog(private val maxEvents: Int) {
    private val events = ArrayDeque<DebugEvent>()

    fun add(event: DebugEvent) {
        events.addFirst(event)
        while (events.size > maxEvents) {
            events.removeLast()
        }
    }

    fun clear() {
        events.clear()
    }

    fun format(category: DebugEventCategory? = null): String =
        snapshot(category).joinToString(separator = "\n") { it.formatted() }

    fun snapshot(category: DebugEventCategory? = null): List<DebugEvent> =
        events.filter { category == null || it.category == category }

    fun size(): Int = events.size
}

fun String.limitForDebug(maxLength: Int): String =
    if (length <= maxLength) {
        this
    } else {
        take((maxLength - 3).coerceAtLeast(0)) + "..."
    }
