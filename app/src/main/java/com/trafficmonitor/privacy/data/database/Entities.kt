package com.trafficmonitor.privacy.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "monitoring_sessions")
data class MonitoringSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val status: String,
    val monitoringMode: String,
    val failureReason: String?,
    val countingMethod: String,
    val nicRxBytes: Long,
    val nicTxBytes: Long,
)

@Entity(
    tableName = "destination_aggregates",
    foreignKeys = [
        ForeignKey(
            entity = MonitoringSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(
            value = ["sessionId", "uid", "packageNamesCsv", "canonicalRemoteIp", "protocol", "remotePort"],
            unique = true,
        ),
    ],
)
data class DestinationAggregateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val uid: Int,
    val attributionStatus: String,
    val packageNamesCsv: String,
    val appLabel: String,
    val remoteIp: String,
    val canonicalRemoteIp: String,
    val domain: String?,
    val domainSource: String,
    val protocol: String,
    val remotePort: Int,
    val connectionCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val category: String,
    val privacyClassification: String,
    val confidence: String,
    val owner: String?,
    val classificationSources: String,
)

@Entity(
    tableName = "application_summaries",
    foreignKeys = [
        ForeignKey(
            entity = MonitoringSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "uid", "packageNamesCsv"], unique = true),
    ],
)
data class ApplicationSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val uid: Int,
    val attributionStatus: String,
    val packageNamesCsv: String,
    val appLabel: String,
    val destinationCount: Int,
    val knownTrackerCount: Int,
    val analyticsCount: Int,
    val advertisingCount: Int,
    val trackingCount: Int,
    val unknownCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
)
