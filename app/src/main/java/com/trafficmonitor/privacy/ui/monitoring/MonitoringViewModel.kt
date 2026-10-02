package com.trafficmonitor.privacy.ui.monitoring

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trafficmonitor.privacy.TrafficMonitorApplication
import com.trafficmonitor.privacy.data.database.MonitoringSessionEntity
import com.trafficmonitor.privacy.monitoring.MonitoringPhase
import com.trafficmonitor.privacy.monitoring.MonitoringVpnService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn

class MonitoringViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = getApplication<TrafficMonitorApplication>().graph
    private val _vpnConsent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    val vpnConsent = _vpnConsent.asSharedFlow()
    val phase = graph.runtime.phase
    val live = graph.runtime.live
    val latestSession = graph.repository.observeLatestFinished()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startMonitoring() {
        val context = getApplication<Application>()
        val consent = VpnService.prepare(context)
        if (consent != null) {
            _vpnConsent.tryEmit(consent)
        } else {
            onVpnConsentGranted()
        }
    }

    fun onVpnConsentGranted() {
        val context = getApplication<Application>()
        context.startForegroundService(
            Intent(context, MonitoringVpnService::class.java).setAction(MonitoringVpnService.ACTION_START),
        )
    }

    fun stopMonitoring() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, MonitoringVpnService::class.java).setAction(MonitoringVpnService.ACTION_STOP),
        )
    }

    fun isRunning(): Boolean = phase.value is MonitoringPhase.Running
}

fun MonitoringSessionEntity.durationMillis(): Long {
    val end = endedAtEpochMs ?: return 0
    return end - startedAtEpochMs
}
