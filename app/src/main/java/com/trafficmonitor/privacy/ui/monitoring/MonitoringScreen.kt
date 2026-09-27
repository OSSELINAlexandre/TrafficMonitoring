package com.trafficmonitor.privacy.ui.monitoring

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trafficmonitor.privacy.data.model.SessionStatus
import com.trafficmonitor.privacy.monitoring.MonitoringPhase
import com.trafficmonitor.privacy.ui.formatBytes
import com.trafficmonitor.privacy.ui.formatDuration
import com.trafficmonitor.privacy.ui.underlaySessionLabel
import kotlinx.coroutines.delay

@Composable
fun MonitoringScreen(
    onViewResults: () -> Unit,
    viewModel: MonitoringViewModel = viewModel(),
) {
    val context = LocalContext.current
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val underlay by viewModel.underlay.collectAsStateWithLifecycle()
    val latest by viewModel.latestSession.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = phase as? MonitoringPhase.Running

    LaunchedEffect(running?.sessionId) {
        while (running != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startMonitoring() }
    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onVpnConsentGranted()
    }
    LaunchedEffect(Unit) {
        viewModel.vpnConsent.collect { vpnConsent.launch(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (running != null) "Surveillance en cours" else "Inactif",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "VPN local, sans serveur distant. Le trafic n'est pas déchiffré et les métadonnées restent sur l'appareil.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        if (phase is MonitoringPhase.Failed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = (phase as MonitoringPhase.Failed).message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (running != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatDuration(now - running.startedAtEpochMs), style = MaterialTheme.typography.headlineSmall)
                    underlaySessionLabel(underlay)?.let { Text(it) }
                    Text("Applications : ${live.applications}")
                    Text("Destinations : ${live.destinations}")
                    Text("Envoyé : ${formatBytes(live.bytesSent)}")
                    Text("Reçu : ${formatBytes(live.bytesReceived)}")
                }
            }
            Button(
                onClick = viewModel::stopMonitoring,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Arrêter") }
        } else {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.startMonitoring()
                    else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Démarrer la surveillance") }
        }

        Text("Périmètre", style = MaterialTheme.typography.titleMedium)
        ModeRow(selected = true, enabled = true, title = "Toutes les applications", subtitle = "Mode par défaut, profil actuel")
        ModeRow(
            selected = false,
            enabled = false,
            title = "Applications choisies",
            subtitle = "Bientôt disponible",
        )

        latest?.let { session ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dernière session", style = MaterialTheme.typography.titleMedium)
                    underlaySessionLabel(session.underlayType)?.let { Text(it) }
                    Text(formatDuration(session.durationMillis()))
                    Text(
                        if (session.status == SessionStatus.COMPLETED) "Terminée" else "Interrompue",
                    )
                    if (!session.failureReason.isNullOrBlank()) {
                        Text(session.failureReason, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = onViewResults, modifier = Modifier.fillMaxWidth()) {
                        Text("Voir les résultats")
                    }
                }
            }
        }
        Text(
            text = "Les volumes sont des estimations de la couche de transfert Firestack, pas les octets IP bruts du TUN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModeRow(selected: Boolean, enabled: Boolean, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
