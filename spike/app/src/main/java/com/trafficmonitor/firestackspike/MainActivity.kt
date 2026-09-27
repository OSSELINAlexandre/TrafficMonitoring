package com.trafficmonitor.firestackspike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val vpnPermissionRequest = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        body.addView(TextView(this).apply {
            textSize = 24f
            text = "Firestack / Android 16 spike"
        })
        body.addView(TextView(this).apply {
            textSize = 16f
            text = """
                Build jetable, sans UI de production.

                1. Démarrer et accepter le VPN.
                2. Tester navigateur TCP, DNS, HTTP/3/QUIC, ping/ICMP et IPv6.
                3. Basculer Wi-Fi ↔ réseau mobile puis revenir.
                4. Arrêter et récupérer les preuves :

                adb shell run-as $packageName cat files/firestack-spike/events.jsonl

                Les paquets bruts ne sont pas enregistrés. Les événements contiennent tuples, UID, DNS et compteurs Firestack.
            """.trimIndent()
        })
        body.addView(Button(this).apply {
            text = "Démarrer le VPN"
            setOnClickListener { requestVpnAndStart() }
        })
        body.addView(Button(this).apply {
            text = "Arrêter le VPN"
            setOnClickListener {
                startService(Intent(this@MainActivity, SpikeVpnService::class.java).apply {
                    action = SpikeVpnService.ACTION_STOP
                })
            }
        })
        body.addView(Button(this).apply {
            text = "Effacer les événements"
            setOnClickListener {
                val deleted = SpikeEvents.file(this@MainActivity).delete()
                Toast.makeText(this@MainActivity, if (deleted) "Journal effacé" else "Journal déjà vide", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }

    @Suppress("DEPRECATION")
    private fun requestVpnAndStart() {
        val request = VpnService.prepare(this)
        if (request == null) startSpike() else startActivityForResult(request, vpnPermissionRequest)
    }

    @Deprecated("Kept deliberately simple for this disposable spike")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnPermissionRequest && resultCode == RESULT_OK) startSpike()
    }

    private fun startSpike() {
        startForegroundService(Intent(this, SpikeVpnService::class.java).apply {
            action = SpikeVpnService.ACTION_START
        })
    }
}
