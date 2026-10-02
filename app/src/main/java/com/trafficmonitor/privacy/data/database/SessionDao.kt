package com.trafficmonitor.privacy.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class FinishUpdate(
    val status: String,
    val endedAtEpochMs: Long,
    val failureReason: String?,
    val nicRxBytes: Long,
    val nicTxBytes: Long,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: MonitoringSessionEntity): Long

    @Query("DELETE FROM destination_aggregates WHERE sessionId = :sessionId")
    suspend fun deleteAggregates(sessionId: Long)

    @Query("DELETE FROM application_summaries WHERE sessionId = :sessionId")
    suspend fun deleteSummaries(sessionId: Long)

    @Insert
    suspend fun insertAggregates(rows: List<DestinationAggregateEntity>)

    @Insert
    suspend fun insertSummaries(rows: List<ApplicationSummaryEntity>)

    @Query(
        """
        UPDATE monitoring_sessions
        SET status = :status,
            endedAtEpochMs = :endedAtEpochMs,
            failureReason = :failureReason,
            nicRxBytes = :nicRxBytes,
            nicTxBytes = :nicTxBytes
        WHERE id = :id
        """,
    )
    suspend fun updateFinished(
        id: Long,
        status: String,
        endedAtEpochMs: Long,
        failureReason: String?,
        nicRxBytes: Long,
        nicTxBytes: Long,
    )

    @Query(
        """
        UPDATE monitoring_sessions
        SET nicRxBytes = :nicRxBytes,
            nicTxBytes = :nicTxBytes
        WHERE id = :id
        """,
    )
    suspend fun updateNic(id: Long, nicRxBytes: Long, nicTxBytes: Long)

    @Query("UPDATE monitoring_sessions SET underlayType = :underlayType WHERE id = :id")
    suspend fun updateUnderlay(id: Long, underlayType: String)

    @Query(
        """
        UPDATE monitoring_sessions
        SET status = 'FAILED',
            endedAtEpochMs = :endedAtEpochMs,
            failureReason = :reason
        WHERE status = 'RUNNING' AND startedAtEpochMs < :startedBeforeEpochMs
        """,
    )
    suspend fun failInterrupted(startedBeforeEpochMs: Long, endedAtEpochMs: Long, reason: String)

    @Query("DELETE FROM monitoring_sessions")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM monitoring_sessions
        WHERE status != 'RUNNING'
        ORDER BY COALESCE(endedAtEpochMs, startedAtEpochMs) DESC
        LIMIT 1
        """,
    )
    fun observeLatestFinished(): Flow<MonitoringSessionEntity?>

    @Query("SELECT * FROM monitoring_sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<MonitoringSessionEntity?>

    @Query(
        """
        SELECT * FROM application_summaries
        WHERE sessionId = :sessionId
        ORDER BY bytesReceived DESC, bytesSent DESC, appLabel ASC
        """,
    )
    fun observeApplications(sessionId: Long): Flow<List<ApplicationSummaryEntity>>

    @Query(
        """
        SELECT * FROM destination_aggregates
        WHERE sessionId = :sessionId AND uid = :uid
        ORDER BY bytesReceived DESC, bytesSent DESC
        """,
    )
    fun observeDestinations(sessionId: Long, uid: Int): Flow<List<DestinationAggregateEntity>>

    @Transaction
    suspend fun checkpoint(
        sessionId: Long,
        aggregates: List<DestinationAggregateEntity>,
        summaries: List<ApplicationSummaryEntity>,
        finish: FinishUpdate?,
        nicRxBytes: Long,
        nicTxBytes: Long,
    ) {
        deleteAggregates(sessionId)
        deleteSummaries(sessionId)
        if (aggregates.isNotEmpty()) insertAggregates(aggregates)
        if (summaries.isNotEmpty()) insertSummaries(summaries)
        if (finish != null) {
            updateFinished(
                id = sessionId,
                status = finish.status,
                endedAtEpochMs = finish.endedAtEpochMs,
                failureReason = finish.failureReason,
                nicRxBytes = finish.nicRxBytes,
                nicTxBytes = finish.nicTxBytes,
            )
        } else {
            updateNic(sessionId, nicRxBytes, nicTxBytes)
        }
    }
}
