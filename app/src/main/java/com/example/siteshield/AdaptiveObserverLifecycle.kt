package com.example.siteshield

/** Pure lifecycle ownership for one active-page observer loop across multiple logical tabs. */
class AdaptiveObserverLifecycle {
    private data class State(
        var generation: Long = 0,
        var active: Boolean = false,
        var periodicPending: Boolean = false,
        var resourceDrainPending: Boolean = false,
    )

    private val states = mutableMapOf<String, State>()

    fun documentStarted(tabId: String): Long = state(tabId).let {
        it.generation += 1
        it.periodicPending = false
        it.resourceDrainPending = false
        it.generation
    }

    fun attach(tabId: String): Long = state(tabId).let {
        states.values.forEach { other -> other.active = false }
        it.active = true
        it.generation
    }

    fun detach(tabId: String) {
        states[tabId]?.apply {
            active = false
            periodicPending = false
            resourceDrainPending = false
        }
    }

    fun pause(tabId: String) {
        states[tabId]?.apply {
            periodicPending = false
            resourceDrainPending = false
        }
    }

    fun destroy(tabId: String) {
        states.remove(tabId)
    }

    fun generation(tabId: String): Long = state(tabId).generation

    fun isCurrentActive(tabId: String, generation: Long): Boolean =
        states[tabId]?.let { it.active && it.generation == generation } == true

    fun schedulePeriodic(tabId: String, generation: Long): Boolean =
        states[tabId]?.takeIf { it.active && it.generation == generation && !it.periodicPending }?.let {
            it.periodicPending = true
            true
        } ?: false

    fun consumePeriodic(tabId: String, generation: Long): Boolean =
        states[tabId]?.let {
            val valid = it.active && it.generation == generation && it.periodicPending
            it.periodicPending = false
            valid
        } == true

    fun scheduleResourceDrain(tabId: String, generation: Long): Boolean =
        states[tabId]?.takeIf { it.active && it.generation == generation && !it.resourceDrainPending }?.let {
            it.resourceDrainPending = true
            true
        } ?: false

    fun consumeResourceDrain(tabId: String, generation: Long): Boolean =
        states[tabId]?.let {
            val valid = it.active && it.generation == generation && it.resourceDrainPending
            it.resourceDrainPending = false
            valid
        } == true

    private fun state(tabId: String): State = states.getOrPut(tabId) { State() }
}

object AdaptiveRuntimeModePolicy {
    fun observes(mode: AdaptiveShieldMode): Boolean = mode != AdaptiveShieldMode.OFF

    fun performsStaticCleanup(blockerEnabled: Boolean): Boolean = blockerEnabled

    fun enforces(mode: AdaptiveShieldMode, blockerEnabled: Boolean): Boolean =
        blockerEnabled && mode == AdaptiveShieldMode.AUTO_SAFE
}
