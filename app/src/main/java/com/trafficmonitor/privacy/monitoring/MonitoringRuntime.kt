package com.trafficmonitor.privacy.monitoring

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface MonitoringPhase {
    data object Idle : MonitoringPhase
    data class Running(val sessionId: Long, val startedAtEpochMs: Long) : MonitoringPhase
    data class Failed(val message: String) : MonitoringPhase
}

class MonitoringRuntime {
    val phase = MutableStateFlow<MonitoringPhase>(MonitoringPhase.Idle)
    val live = MutableStateFlow(LiveCounts())
}
