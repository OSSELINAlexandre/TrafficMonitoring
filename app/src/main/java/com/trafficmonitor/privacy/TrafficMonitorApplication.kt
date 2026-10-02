package com.trafficmonitor.privacy

import android.app.Application

class TrafficMonitorApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
