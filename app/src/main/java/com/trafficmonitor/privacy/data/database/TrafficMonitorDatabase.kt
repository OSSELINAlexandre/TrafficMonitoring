package com.trafficmonitor.privacy.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MonitoringSessionEntity::class,
        DestinationAggregateEntity::class,
        ApplicationSummaryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TrafficMonitorDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        const val NAME = "traffic_monitor.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE monitoring_sessions ADD COLUMN underlayType TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
            }
        }

        fun create(context: Context): TrafficMonitorDatabase =
            Room.databaseBuilder(context.applicationContext, TrafficMonitorDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
