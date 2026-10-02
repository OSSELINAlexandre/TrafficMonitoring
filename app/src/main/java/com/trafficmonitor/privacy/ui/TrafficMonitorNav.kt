package com.trafficmonitor.privacy.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trafficmonitor.privacy.ui.monitoring.MonitoringScreen
import com.trafficmonitor.privacy.ui.results.AppDetailScreen
import com.trafficmonitor.privacy.ui.results.ResultsScreen
import com.trafficmonitor.privacy.ui.settings.SettingsScreen

private object Routes {
    const val Monitoring = "monitoring"
    const val Results = "results"
    const val Settings = "settings"
    const val AppDetail = "results/app/{sessionId}/{uid}"
    fun appDetail(sessionId: Long, uid: Int) = "results/app/$sessionId/$uid"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun TrafficMonitorNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = listOf(
        Tab(Routes.Monitoring, "Surveillance", Icons.Filled.Home),
        Tab(Routes.Results, "Résultats", Icons.AutoMirrored.Filled.List),
        Tab(Routes.Settings, "Réglages", Icons.Filled.Settings),
    )
    val showBar = route in tabs.map { it.route }
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.Monitoring,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Monitoring) {
                MonitoringScreen(onViewResults = {
                    nav.navigate(Routes.Results) { launchSingleTop = true }
                })
            }
            composable(Routes.Results) {
                ResultsScreen(onOpenApp = { sessionId, uid ->
                    nav.navigate(Routes.appDetail(sessionId, uid))
                })
            }
            composable(Routes.Settings) { SettingsScreen() }
            composable(
                route = Routes.AppDetail,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.LongType },
                    navArgument("uid") { type = NavType.IntType },
                ),
            ) { entry ->
                val sessionId = entry.arguments?.getLong("sessionId") ?: return@composable
                val uid = entry.arguments?.getInt("uid") ?: return@composable
                AppDetailScreen(sessionId = sessionId, uid = uid, onBack = { nav.popBackStack() })
            }
        }
    }
}
