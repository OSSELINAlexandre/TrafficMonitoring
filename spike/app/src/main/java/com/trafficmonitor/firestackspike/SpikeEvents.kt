package com.trafficmonitor.firestackspike

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant

object SpikeEvents {
    private const val TAG = "FirestackSpike"
    private const val DIRECTORY = "firestack-spike"
    private const val FILE_NAME = "events.jsonl"

    @Synchronized
    fun write(context: Context, event: String, vararg values: Pair<String, Any?>) {
        val json = JSONObject()
            .put("at", Instant.now().toString())
            .put("event", event)
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }

        val line = json.toString()
        Log.i(TAG, line)
        runCatching {
            val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            File(directory, FILE_NAME).appendText("$line\n")
        }.onFailure { Log.e(TAG, "Cannot persist event", it) }
    }

    fun file(context: Context): File = File(File(context.filesDir, DIRECTORY), FILE_NAME)
}
