package com.trafficmonitor.privacy.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import com.trafficmonitor.privacy.MainActivity
import com.trafficmonitor.privacy.R
import com.trafficmonitor.privacy.TrafficMonitorApplication
import com.trafficmonitor.privacy.data.database.FinishUpdate
import com.trafficmonitor.privacy.data.model.AttributionStatus
import com.trafficmonitor.privacy.data.model.SessionStatus
import com.trafficmonitor.privacy.forwarding.AllowAllPolicyEngine
import com.trafficmonitor.privacy.forwarding.ClosedFlow
import com.trafficmonitor.privacy.forwarding.ForwardingListener
import com.trafficmonitor.privacy.forwarding.ForwardingRequest
import com.trafficmonitor.privacy.forwarding.ObservedDns
import com.trafficmonitor.privacy.forwarding.ObservedFlow
import com.trafficmonitor.privacy.forwarding.UidInjection
import com.trafficmonitor.privacy.forwarding.implementation.FirestackForwardingEngine
import com.trafficmonitor.privacy.forwarding.implementation.SocketGuard
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground local VPN. It owns the TUN and the session lifecycle.
 * Forwarding, attribution and aggregation live in their own types.
 */
class MonitoringVpnService : VpnService() {
    private val lifecycle = Executors.newSingleThreadExecutor()
    private val checkpoints = Executors.newSingleThreadScheduledExecutor()
    private val engine = FirestackForwardingEngine()
    private val domains = DomainResolver()
    private val tracker = FlowTracker(domains)
    private val policy = AllowAllPolicyEngine()
    private val finished = AtomicBoolean(false)

    private val graph by lazy { (application as TrafficMonitorApplication).graph }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val resolver by lazy { AppResolver(connectivity, packageManager, Process.myUid()) }

    @Volatile private var running = false
    @Volatile private var accepting = false
    @Volatile private var foreground = false
    @Volatile private var sessionId: Long? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var checkpointTask: ScheduledFuture<*>? = null
    private lateinit var underlay: UnderlayWatcher

    private val socketGuard = object : SocketGuard {
        override fun protect(fd: Int): Boolean = this@MonitoringVpnService.protect(fd)

        override fun bind(fd: Int): String? {
            val network = underlay.current ?: return "no underlay"
            var adopted: ParcelFileDescriptor? = null
            return try {
                adopted = ParcelFileDescriptor.adoptFd(fd)
                network.bindSocket(adopted.fileDescriptor)
                null
            } catch (error: Throwable) {
                "${error.javaClass.simpleName}:${error.message}"
            } finally {
                runCatching { adopted?.detachFd() }
            }
        }
    }

    private val listener = object : ForwardingListener {
        override fun onFlowOpened(flow: ObservedFlow) {
            if (!accepting) return
            tracker.onFlowOpened(flow, resolver.identityForUid(flow.uid))
            publishLive()
        }

        override fun onFlowClosed(flow: ClosedFlow) {
            if (!accepting) return
            val attribution = resolver.identityForUid(flow.uid)
            tracker.onFlowClosed(flow, attribution)
            publishLive()
            MonitorLog.debug(
                "closed ${Protocols.name(flow.protocol)} uid=${flow.uid} " +
                    "pkg=${attribution.packageCsv()} dst=${flow.destination} " +
                    "rx=${flow.bytesReceived} tx=${flow.bytesSent}",
            )
        }

        override fun onDns(event: ObservedDns) {
            if (!accepting) return
            tracker.onDns(event)
        }

        override fun onFault(message: String) {
            MonitorLog.error(message, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine.setSocketGuard(socketGuard)
        underlay = UnderlayWatcher(connectivity) { snapshot ->
            lifecycle.execute { adopt(snapshot) }
        }
        underlay.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> lifecycle.execute { stopMonitoring("user") }
            else -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification("Démarrage de la surveillance"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
                foreground = true
                lifecycle.execute { startMonitoring() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        MonitorLog.info("vpn revoked")
        lifecycle.execute { stopMonitoring("revoked") }
        super.onRevoke()
    }

    override fun onDestroy() {
        lifecycle.execute { stopMonitoring("destroyed") }
        lifecycle.shutdown()
        runCatching { lifecycle.awaitTermination(3, TimeUnit.SECONDS) }
        if (::underlay.isInitialized) underlay.stop()
        checkpoints.shutdownNow()
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (running) {
            MonitorLog.info("start ignored, already running")
            return
        }
        finished.set(false)
        accepting = true
        tracker.reset()
        val startedAt = System.currentTimeMillis()
        try {
            val id = runBlocking { graph.repository.startSession(startedAt) }
            sessionId = id
            graph.runtime.phase.value = MonitoringPhase.Running(id, startedAt)
            graph.runtime.live.value = LiveCounts()
            val established = Builder()
                .setSession("Moniteur réseau")
                .setMtu(TunProfile.TUN_MTU)
                .setBlocking(false)
                .setMetered(false)
                .addAddress(TunProfile.IPV4_ADDRESS, TunProfile.IPV4_PREFIX)
                .addAddress(TunProfile.IPV6_ADDRESS, TunProfile.IPV6_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(TunProfile.DNS_IPV4)
                .addDnsServer(TunProfile.DNS_IPV6)
                .establish() ?: error("VpnService.Builder.establish a retourné null")
            tun = established
            underlay.current?.let { setUnderlyingNetworks(arrayOf(it)) }
            val link = underlay.latest
            engine.start(
                ForwardingRequest(
                    tunFd = established.fd,
                    linkMtu = link?.mtu ?: UnderlayWatcher.DEFAULT_MTU,
                    tunMtu = TunProfile.TUN_MTU,
                    interfaceAddresses = TunProfile.interfaceAddresses(),
                    fakeDnsEndpoints = TunProfile.fakeDnsEndpoints(),
                    virtualDnsIps = TunProfile.virtualDnsIps(),
                    attributor = { protocol, source, destination ->
                        val attribution = resolver.attribute(protocol, source, destination)
                        UidInjection(
                            uid = attribution.uid,
                            self = attribution.uid == Process.myUid() ||
                                attribution.status == AttributionStatus.SELF,
                        )
                    },
                    listener = listener,
                    policy = policy,
                ),
            )
            running = true
            link?.let { engine.updateUnderlay(it.mtu, it.dnsServers) }
            scheduleCheckpoints()
            updateNotification("Surveillance en cours")
            MonitorLog.info("vpn started session=$id")
        } catch (error: Throwable) {
            MonitorLog.error("vpn start failed", error)
            graph.runtime.phase.value = MonitoringPhase.Failed("La surveillance n'a pas pu démarrer.")
            stopMonitoring("start_failed")
        }
    }

    private fun stopMonitoring(reason: String) {
        running = false
        accepting = false
        checkpointTask?.cancel(false)
        checkpointTask = null
        val id = sessionId
        val nic = engine.nicBytes()
        engine.stop()
        runCatching { tun?.close() }
        tun = null
        val firstFinish = finished.compareAndSet(false, true)
        if (firstFinish && id != null) {
            val failed = reason != "user"
            val message = failureMessage(reason)
            val projection = projectSession(tracker.snapshot(), graph.classifier)
            runBlocking {
                graph.repository.checkpoint(
                    sessionId = id,
                    projection = projection,
                    finish = FinishUpdate(
                        status = if (failed) SessionStatus.FAILED else SessionStatus.COMPLETED,
                        endedAtEpochMs = System.currentTimeMillis(),
                        failureReason = if (failed) message else null,
                        nicRxBytes = nic?.received ?: 0L,
                        nicTxBytes = nic?.sent ?: 0L,
                    ),
                    nicRxBytes = nic?.received ?: 0L,
                    nicTxBytes = nic?.sent ?: 0L,
                )
            }
            MonitorLog.info("session $id saved status=${if (failed) "FAILED" else "COMPLETED"} reason=$reason")
            if (failed && reason != "destroyed") {
                graph.runtime.phase.value = MonitoringPhase.Failed(message)
            } else if (graph.runtime.phase.value is MonitoringPhase.Running) {
                graph.runtime.phase.value = MonitoringPhase.Idle
            }
        }
        sessionId = null
        tracker.reset()
        graph.runtime.live.value = LiveCounts()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (reason != "destroyed") stopSelf()
    }

    private fun checkpoint() {
        val id = sessionId ?: return
        if (!running) return
        val nic = engine.nicBytes()
        val projection = projectSession(tracker.snapshot(), graph.classifier)
        runBlocking {
            graph.repository.checkpoint(
                sessionId = id,
                projection = projection,
                finish = null,
                nicRxBytes = nic?.received ?: 0L,
                nicTxBytes = nic?.sent ?: 0L,
            )
        }
        val live = graph.runtime.live.value
        MonitorLog.info(
            "checkpoint session=$id apps=${live.applications} dests=${live.destinations} " +
                "tx=${live.bytesSent} rx=${live.bytesReceived}",
        )
    }

    private fun scheduleCheckpoints() {
        checkpointTask?.cancel(false)
        checkpointTask = checkpoints.scheduleWithFixedDelay(
            { lifecycle.execute { runCatching { checkpoint() }.onFailure { MonitorLog.error("checkpoint", it) } } },
            CHECKPOINT_SECONDS,
            CHECKPOINT_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun adopt(snapshot: UnderlaySnapshot) {
        if (!running) return
        runCatching { setUnderlyingNetworks(arrayOf(snapshot.network)) }
            .onFailure { MonitorLog.error("underlying networks", it) }
        engine.updateUnderlay(snapshot.mtu, snapshot.dnsServers)
        MonitorLog.info(
            "underlay wifi=${snapshot.wifi} cellular=${snapshot.cellular} mtu=${snapshot.mtu} " +
                "dns=${snapshot.dnsServers.size}",
        )
    }

    private fun publishLive() {
        graph.runtime.live.value = tracker.liveCounts()
    }

    private fun failureMessage(reason: String): String = when (reason) {
        "revoked" -> "Android a révoqué le VPN."
        "destroyed" -> "Session interrompue (service arrêté)."
        "start_failed" -> "La surveillance n'a pas pu démarrer."
        else -> "La surveillance s'est arrêtée de façon inattendue."
    }

    private fun updateNotification(text: String) {
        if (!foreground) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitoringVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle("Surveillance réseau")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Surveillance réseau", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_START = "com.trafficmonitor.privacy.START"
        const val ACTION_STOP = "com.trafficmonitor.privacy.STOP"
        private const val CHANNEL_ID = "network-monitor"
        private const val NOTIFICATION_ID = 36
        private const val CHECKPOINT_SECONDS = 20L
    }
}
