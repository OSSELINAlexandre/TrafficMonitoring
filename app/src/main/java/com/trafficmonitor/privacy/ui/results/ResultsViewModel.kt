package com.trafficmonitor.privacy.ui.results

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trafficmonitor.privacy.TrafficMonitorApplication
import com.trafficmonitor.privacy.data.database.ApplicationSummaryEntity
import com.trafficmonitor.privacy.data.database.DestinationAggregateEntity
import com.trafficmonitor.privacy.data.database.MonitoringSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class CategoryFilter(val label: String) {
    ALL("Toutes"),
    ANALYTICS("Analytique"),
    ADVERTISING("Publicité"),
    TRACKING("Suivi"),
    UNKNOWN("Inconnu"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = getApplication<TrafficMonitorApplication>().graph.repository
    val filter = MutableStateFlow(CategoryFilter.ALL)
    val session = repository.observeLatestFinished()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val applications = session.flatMapLatest { current ->
        if (current == null) flowOf(emptyList()) else repository.observeApplications(current.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: CategoryFilter) {
        filter.value = value
    }
}

class AppDetailViewModel(
    app: Application,
    sessionId: Long,
    uid: Int,
) : AndroidViewModel(app) {
    private val repository = getApplication<TrafficMonitorApplication>().graph.repository
    val session = repository.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val destinations = repository.observeDestinations(sessionId, uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val application = repository.observeApplications(sessionId)
        .map { rows -> rows.firstOrNull { it.uid == uid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

fun ApplicationSummaryEntity.matches(filter: CategoryFilter): Boolean = when (filter) {
    CategoryFilter.ALL -> true
    CategoryFilter.ANALYTICS -> analyticsCount > 0
    CategoryFilter.ADVERTISING -> advertisingCount > 0
    CategoryFilter.TRACKING -> trackingCount > 0
    CategoryFilter.UNKNOWN -> unknownCount > 0
}

fun DestinationAggregateEntity.matches(filter: CategoryFilter): Boolean = when (filter) {
    CategoryFilter.ALL -> true
    CategoryFilter.ANALYTICS -> category == "ANALYTICS"
    CategoryFilter.ADVERTISING -> category == "ADVERTISING"
    CategoryFilter.TRACKING -> category == "TRACKING"
    CategoryFilter.UNKNOWN -> category == "UNKNOWN"
}
