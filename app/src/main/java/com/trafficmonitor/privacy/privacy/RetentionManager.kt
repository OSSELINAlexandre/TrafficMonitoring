package com.trafficmonitor.privacy.privacy

import android.content.Context
import com.trafficmonitor.privacy.data.database.TrafficMonitorDatabase
import com.trafficmonitor.privacy.data.repository.SessionRepository
import java.io.File

/**
 * Storage policy from the product spec. Automatic deletion of old sessions is not
 * enforced yet; erase-all is available and the running session is never a target
 * of that action (the UI refuses it while monitoring).
 */
class RetentionManager(
    private val context: Context,
    private val repository: SessionRepository,
) {
    val defaultLimitBytes: Long = 500L * 1024 * 1024
    val defaultRetentionDays: Int = 30

    fun databaseBytes(): Long {
        val db = context.getDatabasePath(TrafficMonitorDatabase.NAME)
        return listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
            .sumOf { file -> if (file.exists()) file.length() else 0L }
    }

    fun databasePath(): String = context.getDatabasePath(TrafficMonitorDatabase.NAME).absolutePath

    suspend fun eraseAll() {
        repository.eraseAll()
    }
}
