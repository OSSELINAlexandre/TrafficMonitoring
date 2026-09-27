package com.trafficmonitor.privacy.monitoring

import android.util.Log

object MonitorLog {
    const val TAG = "TrafficMonitor"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun debug(message: String) {
        Log.d(TAG, message)
    }

    fun error(message: String, error: Throwable?) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
    }
}
