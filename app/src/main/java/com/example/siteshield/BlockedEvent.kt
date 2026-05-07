package com.example.siteshield

data class BlockedEvent(
    val type: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)
