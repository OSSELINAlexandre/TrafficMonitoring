package com.trafficmonitor.privacy.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MonitoringSessionEntity::class,
        DestinationAggregateEntity::class,
        ApplicationSummaryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TrafficMonitorDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        const val NAME = "traffic_monitor.db"

        fun create(context: Context): TrafficMonitorDatabase =
            Room.databaseBuilder(context.applicationContext, TrafficMonitorDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
