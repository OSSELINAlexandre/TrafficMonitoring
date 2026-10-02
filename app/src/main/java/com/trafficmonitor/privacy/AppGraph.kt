package com.trafficmonitor.privacy

import android.content.Context
import com.trafficmonitor.privacy.classification.StubDestinationClassifier
import com.trafficmonitor.privacy.data.database.TrafficMonitorDatabase
import com.trafficmonitor.privacy.data.repository.SessionRepository
import com.trafficmonitor.privacy.monitoring.MonitoringRuntime
import com.trafficmonitor.privacy.privacy.RetentionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(context: Context) {
    val processStartedAtEpochMs: Long = System.currentTimeMillis()
    val database: TrafficMonitorDatabase = TrafficMonitorDatabase.create(context)
    val repository = SessionRepository(database)
    val retention = RetentionManager(context, repository)
    val runtime = MonitoringRuntime()
    val classifier = StubDestinationClassifier()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { repository.failInterrupted(processStartedAtEpochMs) }
    }
}
