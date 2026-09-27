package com.trafficmonitor.firestackspike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.DomainOpts
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.FlowSummary
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.Tunnel
import com.celzero.firestack.settings.Settings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SpikeVpnService : VpnService() {
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val connectionIds = AtomicLong()
    private val underlayCandidates = ConcurrentHashMap.newKeySet<Network>()

    @Volatile private var underlyingNetwork: Network? = null
    @Volatile private var linkMtu: Int = DEFAULT_MTU
    @Volatile private var tunnel: Tunnel? = null
    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var callbackRegistered = false
    @Volatile private var statsTask: ScheduledFuture<*>? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            underlayCandidates += network
            selectPreferredUnderlay("available")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            underlayCandidates += network
            selectPreferredUnderlay("capabilities_changed")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val mtu = properties.mtu.takeIf { it > 0 } ?: DEFAULT_MTU
            if (network == underlyingNetwork) {
                linkMtu = mtu
                runCatching { tunnel?.setLinkMtu(mtu.toLong()) }
                event("underlay_link", "network" to network.networkHandle, "mtu" to mtu,
                    "addresses" to properties.linkAddresses.joinToString(","))
            }
        }

        override fun onLost(network: Network) {
            underlayCandidates -= network
            event("underlay_lost", "network" to network.networkHandle,
                "was_selected" to (network == underlyingNetwork))
            if (network == underlyingNetwork) {
                underlyingNetwork = null
                selectPreferredUnderlay("fallback_after_loss")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerUnderlayCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.execute { stopSpike("user") }
            else -> {
                startForeground(NOTIFICATION_ID, notification("Starting Firestack"))
                worker.execute { startSpike() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        event("vpn_revoked")
        worker.execute { stopSpike("revoked") }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopSpike("destroyed")
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        worker.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun startSpike() {
        if (tunnel?.isConnected == true) {
            event("start_ignored", "reason" to "already_connected")
            return
        }

        try {
            selectInitialUnderlay()
            Settings.setDebug(true)
            Settings.defaultTunMode()
            Settings.dupTunFd(true)
            Intra.setSELF_UID(Process.myUid().toString())

            val pfd = Builder()
                .setSession("Firestack Android 16 spike")
                .setMtu(TUN_MTU)
                .setBlocking(false)
                .setMetered(false)
                .addAddress(TUN_V4, 24)
                .addAddress(TUN_V6, 120)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(DNS_V4)
                .addDnsServer(DNS_V6)
                .establish() ?: error("VpnService.Builder.establish returned null")

            tunDescriptor = pfd
            underlyingNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }

            // connect() is intentional: the pinned revision's connect2/connect3 path supplies
            // a negative link MTU and can construct a second tunnel. A positive link MTU avoids it.
            val created = Intra.connect(
                pfd.fd.toLong(),
                linkMtu.toLong().coerceAtLeast(1280),
                TUN_MTU.toLong(),
                "$TUN_V4/24,$TUN_V6/120",
                // Firestack parses fake DNS values as netip.AddrPort. Bare addresses are
                // silently rejected and would be forwarded back towards the virtual DNS.
                "$DNS_V4:53,[$DNS_V6]:53",
                Intra.newBuiltinDefaultDNS(),
                SpikeBridge()
            )
            tunnel = created
            setSystemDns(created, underlyingNetwork, "startup")
            event("vpn_started",
                "firestack_build" to Intra.build(false),
                "tun_mtu" to TUN_MTU,
                "link_mtu" to linkMtu,
                "underlay" to underlyingNetwork?.networkHandle,
                "tun_fd_duplicated" to true,
                "pcap" to "disabled")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Firestack is forwarding traffic"))
            scheduleStats()
        } catch (error: Throwable) {
            event("vpn_start_failed", "type" to error.javaClass.name, "message" to error.message)
            stopSpike("start_failed")
        }
    }

    @Synchronized
    private fun stopSpike(reason: String) {
        val currentTunnel = tunnel
        tunnel = null
        runCatching { currentTunnel?.disconnect() }
            .onFailure { event("disconnect_failed", "message" to it.message) }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        statsTask?.cancel(false)
        statsTask = null
        event("vpn_stopped", "reason" to reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (reason != "destroyed") stopSelf()
    }

    private fun scheduleStats() {
        statsTask?.cancel(false)
        statsTask = worker.scheduleWithFixedDelay({
            val current = tunnel ?: return@scheduleWithFixedDelay
            runCatching {
                val stat = current.stat()
                val nic = stat.nic()
                val ip = stat.ip()
                val tcp = stat.tcp()
                val udp = stat.udp()
                val icmp = stat.icmp()
                event("tunnel_stats",
                    "nic_rx" to nic.rx, "nic_tx" to nic.tx,
                    "nic_rx_packets" to nic.rxPkts, "nic_tx_packets" to nic.txPkts,
                    "nic_drops" to nic.drops,
                    "ip_received" to ip.rcv, "ip_sent" to ip.snd,
                    "tcp_received" to tcp.rcv, "tcp_sent" to tcp.snd,
                    "tcp_retransmits" to tcp.retrans,
                    "udp_received" to udp.rcv, "udp_sent" to udp.snd,
                    "icmp4_received" to icmp.rcv4, "icmp4_sent" to icmp.snd4,
                    "icmp6_received" to icmp.rcv6, "icmp6_sent" to icmp.snd6)
            }.onFailure { event("stats_failed", "message" to it.message) }
        }, 5, 10, TimeUnit.SECONDS)
    }

    private fun registerUnderlayCallback() {
        if (callbackRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, networkCallback)
        callbackRegistered = true
        selectInitialUnderlay()
    }

    private fun selectInitialUnderlay() {
        connectivity.allNetworks.filterTo(underlayCandidates) { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
                connectivity.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        selectPreferredUnderlay("initial")
    }

    private fun selectPreferredUnderlay(reason: String) {
        val candidate = underlayCandidates
            .mapNotNull { it to (connectivity.getNetworkCapabilities(it) ?: return@mapNotNull null) }
            .filter { (_, caps) ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .maxByOrNull { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 10 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 5 else 0)
            }
            ?.first
        if (candidate != null && candidate != underlyingNetwork) adoptUnderlying(candidate, reason)
    }

    private fun adoptUnderlying(network: Network, reason: String) {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
        underlyingNetwork = network
        val properties = connectivity.getLinkProperties(network)
        linkMtu = properties?.mtu?.takeIf { it > 0 } ?: DEFAULT_MTU
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
        runCatching { tunnel?.setLinkMtu(linkMtu.toLong()) }
        tunnel?.let { setSystemDns(it, network, "underlay_changed") }
        event("underlay_selected", "reason" to reason, "network" to network.networkHandle,
            "mtu" to linkMtu, "wifi" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            "cellular" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    private fun setSystemDns(activeTunnel: Tunnel, network: Network?, reason: String) {
        val servers = network?.let(connectivity::getLinkProperties)
            ?.dnsServers
            ?.joinToString(",") { it.hostAddress.orEmpty() }
            .orEmpty()
        runCatching { Intra.setSystemDNS(activeTunnel, servers) }
            .onSuccess { event("system_dns_set", "reason" to reason, "servers" to servers) }
            .onFailure {
                event("system_dns_failed", "reason" to reason, "servers" to servers,
                    "message" to it.message)
            }
    }

    private fun notification(text: String): Notification {
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpikeVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Firestack spike")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Firestack spike", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun event(name: String, vararg values: Pair<String, Any?>) =
        SpikeEvents.write(this, name, *values)

    private inner class SpikeBridge : Bridge {
        override fun protect(who: String?, fd: Long) {
            val protected = this@SpikeVpnService.protect(fd.toInt())
            event("socket_protect", "who" to who, "fd" to fd, "success" to protected)
        }

        override fun bind4(who: String, addrPort: String, fid: Long) = bind(who, addrPort, fid, 4)
        override fun bind6(who: String, addrPort: String, fid: Long) = bind(who, addrPort, fid, 6)

        private fun bind(who: String, addrPort: String, fid: Long, family: Int) {
            val protected = this@SpikeVpnService.protect(fid.toInt())
            val selected = underlyingNetwork
            var adopted: ParcelFileDescriptor? = null
            var bound = false
            var failure: String? = null
            try {
                if (selected == null) error("No non-VPN underlying network")
                adopted = ParcelFileDescriptor.adoptFd(fid.toInt())
                selected.bindSocket(adopted.fileDescriptor)
                bound = true
            } catch (error: Throwable) {
                failure = "${error.javaClass.simpleName}:${error.message}"
            } finally {
                runCatching { adopted?.detachFd() }
            }
            event("socket_bind", "who" to who, "remote" to addrPort, "fd" to fid,
                "family" to family, "protected" to protected, "bound" to bound,
                "underlay" to selected?.networkHandle, "failure" to failure)
        }

        override fun preflow(protocol: Int, uid: Int, src: String?, dst: String?): PreMark {
            val resolution = resolveOwner(protocol, src, dst)
            event("preflow", "protocol" to protocolName(protocol), "protocol_number" to protocol,
                "source" to src, "destination" to dst, "firestack_uid_hint" to uid,
                "resolved_uid" to resolution.uid, "uid_status" to resolution.status,
                "packages" to resolution.packages, "lookup_attempts" to resolution.attempts)
            return PreMark().apply {
                this.uid = resolution.uid.toString()
                isUidSelf = resolution.uid == Process.myUid()
            }
        }

        override fun flow(
            protocol: Int, uid: Int, src: String?, dst: String?, realIps: String?, domain: String?,
            probableDomains: String?, blocklists: String?, isAlg: Boolean
        ): Mark {
            val id = "spike-${connectionIds.incrementAndGet()}"
            val fakeDnsPort = fakeDnsPort(dst)
            val proxy = when (fakeDnsPort) {
                53 -> Backend.Base // lets Firestack's dnsOverride consume the query
                853 -> Backend.Block // the virtual endpoint does not terminate DNS-over-TLS
                else -> Backend.Exit
            }
            event("flow", "id" to id, "protocol" to protocolName(protocol), "uid" to uid,
                "source" to src, "destination" to dst, "real_ips" to realIps,
                "domain" to domain, "probable_domains" to probableDomains, "is_alg" to isAlg,
                "proxy_decision" to proxy)
            return directMark(id, uid, proxy)
        }

        override fun inflow(protocol: Int, uid: Int, src: String?, dst: String?): Mark {
            val id = "in-${connectionIds.incrementAndGet()}"
            event("inflow", "id" to id, "protocol" to protocolName(protocol), "uid" to uid,
                "source" to src, "destination" to dst)
            return directMark(id, uid)
        }

        private fun directMark(id: String, uid: Int, proxy: String = Backend.Exit) = Mark().apply {
            pidcsv = proxy
            cid = id
            this.uid = uid.toString()
            ip = ""
        }

        override fun flowing(mark: Mark?) {
            event("flowing", "id" to mark?.cid, "uid" to mark?.uid, "proxy" to mark?.pidcsv)
        }

        override fun postflow(summary: FlowSummary?) {
            if (summary == null) return event("postflow_missing")
            event("postflow", "id" to summary.id, "protocol" to summary.proto,
                "uid" to summary.uid, "source" to summary.source, "target" to summary.target,
                "proxy" to summary.pid, "rx" to summary.rx, "tx" to summary.tx,
                "duration_ms" to summary.duration, "rtt_ms" to summary.rtt, "message" to summary.msg)
        }

        override fun onQuery(origin: String, uidGostr: String, fqdn: String, qtype: Long): DNSOpts {
            event("dns_query", "origin" to origin, "uid" to uidGostr, "name" to fqdn, "qtype" to qtype)
            return DNSOpts().apply {
                uid = uidGostr
                ipcsv = ""
                tidcsv = Backend.System
                tidseccsv = ""
                noblock = true
            }
        }

        override fun onResponse(summary: DNSSummary?) {
            if (summary == null) return event("dns_response_missing")
            event("dns_response", "id" to summary.id, "uid" to summary.uid,
                "name" to summary.qName, "qtype" to summary.qType, "answers" to summary.rData,
                "targets" to summary.targets, "rcode" to summary.rCode, "ttl" to summary.rTtl,
                "cached" to summary.cached, "latency_ms" to summary.latency,
                "server" to summary.server, "message" to summary.msg)
        }

        override fun onPrequery(a: String, b: String, c: String, qtype: Long): DomainOpts? = null
        override fun onUpstreamAnswer(id: String, summary: DNSSummary, opts: DNSOpts, ipcsv: String): DNSOpts {
            event("dns_upstream_answer", "id" to id, "name" to summary.qName, "ips" to ipcsv)
            return opts
        }

        override fun svcRoute(sid: String, pid: String, network: String, sipport: String, dipport: String) = Tab()
        override fun onSvcComplete(summary: ServerSummary) = Unit
        override fun onDNSAdded(id: String?) = event("dns_added", "id" to id)
        override fun onDNSRemoved(id: String?) = event("dns_removed", "id" to id)
        override fun onDNSStopped() = event("dns_stopped")
        override fun onProxiesStopped() = event("proxies_stopped")
        override fun onProxyAdded(pid: String?, handle: String) = event("proxy_added", "id" to pid, "handle" to handle)
        override fun onProxyRemoved(pid: String?, handle: String) = event("proxy_removed", "id" to pid, "handle" to handle)
        override fun onProxyStopped(pid: String?, handle: String) = event("proxy_stopped", "id" to pid, "handle" to handle)
        override fun onProxyUpdated(pid: String, handle: String) = event("proxy_updated", "id" to pid, "handle" to handle)
    }

    private data class OwnerResult(val uid: Int, val status: String, val packages: String, val attempts: String)

    private fun resolveOwner(protocol: Int, source: String?, destination: String?): OwnerResult {
        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) {
            return OwnerResult(INVALID_UID, "UNSUPPORTED_PROTOCOL", "", "none")
        }
        val local = parseAddress(source) ?: return OwnerResult(INVALID_UID, "INVALID_SOURCE", "", "parse")
        val remote = parseAddress(destination) ?: return OwnerResult(INVALID_UID, "INVALID_DESTINATION", "", "parse")
        val attempts = mutableListOf<String>()
        var found = ownerUid(protocol, local, remote, attempts, "exact")
        if (found == INVALID_UID && protocol == OsConstants.IPPROTO_UDP) {
            found = ownerUid(protocol, local, InetSocketAddress(remote.address, 0), attempts, "udp_port_zero")
        }
        if (found == INVALID_UID && protocol == OsConstants.IPPROTO_UDP) {
            val any = if (remote.address.address.size == 16) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
            found = ownerUid(protocol, local, InetSocketAddress(any, 0), attempts, "udp_unspecified")
        }
        val packages = if (found >= 0) packageManager.getPackagesForUid(found)?.joinToString(",").orEmpty() else ""
        val status = when {
            found == INVALID_UID -> "UNKNOWN"
            found == Process.myUid() -> "SELF"
            packages.contains(',') -> "SHARED_UID"
            packages.isEmpty() -> "UID_WITHOUT_VISIBLE_PACKAGE"
            else -> "RESOLVED"
        }
        return OwnerResult(found, status, packages, attempts.joinToString(","))
    }

    private fun ownerUid(
        protocol: Int, local: InetSocketAddress, remote: InetSocketAddress,
        attempts: MutableList<String>, label: String
    ): Int = try {
        val uid = connectivity.getConnectionOwnerUid(protocol, local, remote)
        attempts += "$label:$uid"
        uid
    } catch (error: Throwable) {
        attempts += "$label:${error.javaClass.simpleName}"
        INVALID_UID
    }

    private fun parseAddress(value: String?): InetSocketAddress? = runCatching {
        require(!value.isNullOrBlank())
        val (host, portText) = if (value.startsWith("[")) {
            val closing = value.indexOf(']')
            require(closing > 0 && value.getOrNull(closing + 1) == ':')
            value.substring(1, closing) to value.substring(closing + 2)
        } else {
            val colon = value.lastIndexOf(':')
            require(colon > 0)
            value.substring(0, colon) to value.substring(colon + 1)
        }
        InetSocketAddress(InetAddress.getByName(host.substringBefore('%')), portText.toInt())
    }.getOrNull()

    private fun fakeDnsPort(value: String?): Int? {
        val address = parseAddress(value) ?: return null
        val isFake = address.address == InetAddress.getByName(DNS_V4) ||
            address.address == InetAddress.getByName(DNS_V6)
        return address.port.takeIf { isFake }
    }

    private fun protocolName(protocol: Int): String = when (protocol) {
        OsConstants.IPPROTO_TCP -> "TCP"
        OsConstants.IPPROTO_UDP -> "UDP"
        OsConstants.IPPROTO_ICMP -> "ICMP"
        OsConstants.IPPROTO_ICMPV6 -> "ICMPv6"
        else -> "IP-$protocol"
    }

    companion object {
        const val ACTION_START = "com.trafficmonitor.firestackspike.START"
        const val ACTION_STOP = "com.trafficmonitor.firestackspike.STOP"
        private const val CHANNEL_ID = "firestack-spike"
        private const val NOTIFICATION_ID = 41
        private const val TUN_V4 = "10.111.222.1"
        private const val DNS_V4 = "10.111.222.3"
        private const val TUN_V6 = "fd66:f83a:c650::1"
        private const val DNS_V6 = "fd66:f83a:c650::3"
        private const val TUN_MTU = 1500
        private const val DEFAULT_MTU = 1500
        private const val INVALID_UID = -1
    }
}
