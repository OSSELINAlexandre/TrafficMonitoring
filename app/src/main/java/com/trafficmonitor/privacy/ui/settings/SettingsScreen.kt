package com.trafficmonitor.privacy.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trafficmonitor.privacy.TrafficMonitorApplication
import com.trafficmonitor.privacy.forwarding.implementation.FirestackPin
import com.trafficmonitor.privacy.monitoring.MonitorLog
import com.trafficmonitor.privacy.monitoring.MonitoringPhase
import com.trafficmonitor.privacy.ui.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = getApplication<TrafficMonitorApplication>().graph
    val usedBytes = MutableStateFlow(graph.retention.databaseBytes())
    val path = graph.retention.databasePath()
    val limitBytes = graph.retention.defaultLimitBytes
    val retentionDays = graph.retention.defaultRetentionDays
    val phase = graph.runtime.phase
    val message = MutableStateFlow<String?>(null)

    fun refresh() {
        usedBytes.value = graph.retention.databaseBytes()
    }

    fun erase() {
        if (phase.value is MonitoringPhase.Running) {
            message.value = "Arrêtez la surveillance avant d'effacer l'historique."
            return
        }
        viewModelScope.launch {
            graph.retention.eraseAll()
            refresh()
            message.value = "Historique effacé."
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val used by viewModel.usedBytes.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Effacer l'historique") },
            text = { Text("Toutes les sessions enregistrées sur cet appareil seront supprimées.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.erase()
                }) { Text("Effacer") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Annuler") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        Text("Limite de stockage")
        Text(formatBytes(viewModel.limitBytes))
        Text("Rétention")
        Text("${viewModel.retentionDays} jours")
        Text(
            "Le nettoyage automatique n'est pas encore actif. L'effacement manuel supprime toutes les sessions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Espace utilisé")
        Text("${formatBytes(used)} / ${formatBytes(viewModel.limitBytes)}")
        Button(
            onClick = { confirm = true },
            enabled = phase !is MonitoringPhase.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Effacer l'historique") }
        message?.let { Text(it) }
        Text("Moteur de transfert", style = MaterialTheme.typography.titleMedium)
        Text(FirestackPin.COORDINATE, style = MaterialTheme.typography.bodySmall)
        Text(FirestackPin.COMMIT, style = MaterialTheme.typography.bodySmall)
        Text("Licence ${FirestackPin.LICENSE}", style = MaterialTheme.typography.bodySmall)
        Text("Base locale", style = MaterialTheme.typography.titleMedium)
        Text(viewModel.path, style = MaterialTheme.typography.bodySmall)
        Text("Journaux", style = MaterialTheme.typography.titleMedium)
        Text(
            "adb logcat -s ${MonitorLog.TAG}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Aucun contenu de paquet n'est enregistré. Rien n'est envoyé hors de l'appareil.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
            Text("Actualiser l'espace utilisé")
        }
    }
}
