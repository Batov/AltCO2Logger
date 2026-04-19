package com.altco2.logger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LoggerStatus(
    val running: Boolean = false,
    val stateText: String = "Idle",
    val connectedDevice: String? = null,
    val latestCo2Ppm: Int? = null,
    val latestTempCentiDeg: Int? = null,
    val lastUpdateMs: Long? = null,
)

object LoggerStatusStore {
    private val _state = MutableStateFlow(LoggerStatus())
    val state = _state.asStateFlow()

    fun update(transform: (LoggerStatus) -> LoggerStatus) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = LoggerStatus()
    }
}
