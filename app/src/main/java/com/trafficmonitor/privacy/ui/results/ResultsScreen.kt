package com.trafficmonitor.privacy.ui.results

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trafficmonitor.privacy.data.database.ApplicationSummaryEntity
import com.trafficmonitor.privacy.data.database.DestinationAggregateEntity
import com.trafficmonitor.privacy.data.model.SessionStatus
import com.trafficmonitor.privacy.ui.attributionLabel
import com.trafficmonitor.privacy.ui.categoryLabel
import com.trafficmonitor.privacy.ui.formatBytes
import com.trafficmonitor.privacy.ui.formatDuration
import com.trafficmonitor.privacy.ui.formatWhen
import com.trafficmonitor.privacy.ui.underlaySessionLabel
import com.trafficmonitor.privacy.ui.monitoring.durationMillis
import com.trafficmonitor.privacy.ui.privacyLabel

@Composable
fun ResultsScreen(
    onOpenApp: (sessionId: Long, uid: Int) -> Unit,
    viewModel: ResultsViewModel = viewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val apps by viewModel.applications.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val current = session
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Résultats", style = MaterialTheme.typography.headlineMedium)
            Text("Aucune session terminée. Démarrez une surveillance, utilisez d'autres applications, puis arrêtez pour voir les destinations.")
        }
        return
    }
    val visible = apps.filter { it.matches(filter) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Dernière session", style = MaterialTheme.typography.headlineMedium)
            Text(formatWhen(current.startedAtEpochMs), style = MaterialTheme.typography.bodyMedium)
            underlaySessionLabel(current.underlayType)?.let { Text(it) }
            Text(formatDuration(current.durationMillis()))
            if (current.status != SessionStatus.COMPLETED) {
                Text(
                    current.failureReason ?: "Cette session ne s'est pas terminée normalement.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("${apps.size} applications · ${apps.sumOf { it.destinationCount }} destinations")
            Text("Envoyé ${formatBytes(apps.sumOf { it.bytesSent })} · Reçu ${formatBytes(apps.sumOf { it.bytesReceived })}")
            Text(
                "Historique sur plusieurs semaines : plus tard. Cet écran montre la dernière session enregistrée.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            FilterRow(selected = filter, onSelect = viewModel::setFilter)
        }
        if (visible.isEmpty()) {
            item { Text("Aucune application dans ce filtre.") }
        }
        items(visible, key = { "${it.uid}:${it.packageNamesCsv}" }) { app ->
            AppRow(app) { onOpenApp(current.id, app.uid) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(selected: CategoryFilter, onSelect: (CategoryFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CategoryFilter.entries) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun AppRow(app: ApplicationSummaryEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.appLabel, style = MaterialTheme.typography.titleMedium)
            if (app.attributionStatus != "RESOLVED") {
                Text(attributionLabel(app.attributionStatus), style = MaterialTheme.typography.bodySmall)
            }
            Text("${app.destinationCount} destinations · ${app.knownTrackerCount} traqueurs connus")
            Text("Envoyé ${formatBytes(app.bytesSent)} · Reçu ${formatBytes(app.bytesReceived)}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(sessionId: Long, uid: Int, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: AppDetailViewModel = viewModel(
        key = "$sessionId:$uid",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppDetailViewModel(app, sessionId, uid) as T
        },
    )
    val summary by viewModel.application.collectAsStateWithLifecycle()
    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(summary?.appLabel ?: "Application", style = MaterialTheme.typography.headlineSmall)
            }
            summary?.let { row ->
                if (row.packageNamesCsv.isNotBlank()) {
                    Text(row.packageNamesCsv, style = MaterialTheme.typography.bodySmall)
                }
                Text("${row.destinationCount} destinations · ${row.knownTrackerCount} traqueurs connus")
                Text("Envoyé ${formatBytes(row.bytesSent)} · Reçu ${formatBytes(row.bytesReceived)}")
            }
        }
        if (destinations.isEmpty()) {
            item { Text("Aucune destination enregistrée pour cette application.") }
        }
        items(destinations, key = { it.id }) { destination ->
            DestinationRow(destination)
        }
    }
}

@Composable
private fun DestinationRow(row: DestinationAggregateEntity) {
    val title = row.domain ?: row.remoteIp
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (row.domain != null) {
                Text(row.remoteIp, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (row.domainSource == "DNS_OBSERVED") "Nom observé via DNS" else "Adresse IP seule",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Destination inconnue (adresse IP seule)", style = MaterialTheme.typography.bodySmall)
            }
            row.owner?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(categoryLabel(row.category))
            if (row.privacyClassification != "UNCLASSIFIED") {
                Text("${privacyLabel(row.privacyClassification)} · confiance ${row.confidence.lowercase()}")
            }
            Text("${row.protocol} ${row.remotePort} · ${row.connectionCount} connexions")
            Text("Envoyé ${formatBytes(row.bytesSent)} · Reçu ${formatBytes(row.bytesReceived)}")
        }
    }
}
