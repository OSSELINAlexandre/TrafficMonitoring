package com.trafficmonitor.privacy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trafficmonitor.privacy.ui.TrafficMonitorNav
import com.trafficmonitor.privacy.ui.theme.TrafficMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrafficMonitorTheme {
                TrafficMonitorNav()
            }
        }
    }
}
