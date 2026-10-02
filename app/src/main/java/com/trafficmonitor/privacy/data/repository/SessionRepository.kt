package com.trafficmonitor.privacy.data.repository

import com.trafficmonitor.privacy.data.database.ApplicationSummaryEntity
import com.trafficmonitor.privacy.data.database.DestinationAggregateEntity
import com.trafficmonitor.privacy.data.database.FinishUpdate
import com.trafficmonitor.privacy.data.database.MonitoringSessionEntity
import com.trafficmonitor.privacy.data.database.TrafficMonitorDatabase
import com.trafficmonitor.privacy.data.model.ApplicationSummaryDraft
import com.trafficmonitor.privacy.data.model.CountingMethod
import com.trafficmonitor.privacy.data.model.DestinationDraft
import com.trafficmonitor.privacy.data.model.MonitoringMode
import com.trafficmonitor.privacy.data.model.SessionProjection
import com.trafficmonitor.privacy.data.model.SessionStatus
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val database: TrafficMonitorDatabase) {
    private val dao = database.sessions()

    suspend fun startSession(startedAtEpochMs: Long, underlayType: String): Long = dao.insertSession(
        MonitoringSessionEntity(
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = null,
            status = SessionStatus.RUNNING,
            monitoringMode = MonitoringMode.ALL_APPS,
            failureReason = null,
            countingMethod = CountingMethod.FIRESTACK_FLOW_RX_TX,
            nicRxBytes = 0,
            nicTxBytes = 0,
            underlayType = underlayType,
        ),
    )

    suspend fun updateUnderlay(sessionId: Long, underlayType: String) {
        dao.updateUnderlay(sessionId, underlayType)
    }

    suspend fun checkpoint(
        sessionId: Long,
        projection: SessionProjection,
        finish: FinishUpdate?,
        nicRxBytes: Long,
        nicTxBytes: Long,
    ) {
        dao.checkpoint(
            sessionId = sessionId,
            aggregates = projection.destinations.map { it.toEntity(sessionId) },
            summaries = projection.applications.map { it.toEntity(sessionId) },
            finish = finish,
            nicRxBytes = nicRxBytes,
            nicTxBytes = nicTxBytes,
        )
    }

    suspend fun failInterrupted(processStartedAtEpochMs: Long) {
        dao.failInterrupted(
            startedBeforeEpochMs = processStartedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            reason = "Session interrompue avant un arrêt normal.",
        )
    }

    suspend fun eraseAll() {
        dao.deleteAll()
    }

    fun observeLatestFinished(): Flow<MonitoringSessionEntity?> = dao.observeLatestFinished()

    fun observeSession(id: Long): Flow<MonitoringSessionEntity?> = dao.observeSession(id)

    fun observeApplications(sessionId: Long): Flow<List<ApplicationSummaryEntity>> =
        dao.observeApplications(sessionId)

    fun observeDestinations(sessionId: Long, uid: Int): Flow<List<DestinationAggregateEntity>> =
        dao.observeDestinations(sessionId, uid)

    private fun DestinationDraft.toEntity(sessionId: Long) = DestinationAggregateEntity(
        sessionId = sessionId,
        uid = uid,
        attributionStatus = attributionStatus.name,
        packageNamesCsv = packages.joinToString(","),
        appLabel = label,
        remoteIp = remoteIp,
        canonicalRemoteIp = remoteIp,
        domain = domain,
        domainSource = domainSource.name,
        protocol = protocol,
        remotePort = remotePort,
        connectionCount = connectionCount,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        category = category.name,
        privacyClassification = privacyClassification.name,
        confidence = confidence.name,
        owner = owner,
        classificationSources = classificationSources.joinToString(","),
    )

    private fun ApplicationSummaryDraft.toEntity(sessionId: Long) = ApplicationSummaryEntity(
        sessionId = sessionId,
        uid = uid,
        attributionStatus = attributionStatus.name,
        packageNamesCsv = packages.joinToString(","),
        appLabel = label,
        destinationCount = destinationCount,
        knownTrackerCount = knownTrackerCount,
        analyticsCount = analyticsCount,
        advertisingCount = advertisingCount,
        trackingCount = trackingCount,
        unknownCount = unknownCount,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
    )
}
