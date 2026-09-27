package com.trafficmonitor.privacy.forwarding.implementation

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
import com.trafficmonitor.privacy.forwarding.ClosedFlow
import com.trafficmonitor.privacy.forwarding.EndpointParser
import com.trafficmonitor.privacy.forwarding.ForwardingEngine
import com.trafficmonitor.privacy.forwarding.ForwardingRequest
import com.trafficmonitor.privacy.forwarding.NicBytes
import com.trafficmonitor.privacy.forwarding.ObservedDns
import com.trafficmonitor.privacy.forwarding.ObservedFlow
import com.trafficmonitor.privacy.forwarding.PolicyDecision
import com.trafficmonitor.privacy.forwarding.PolicyRequest
import com.trafficmonitor.privacy.monitoring.MonitorLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Firestack adapter. The rest of the app talks to [ForwardingEngine] only.
 * PCAP is never enabled. Fake-DNS port 53 stays inside Firestack (`Backend.Base`);
 * other ports on the virtual DNS addresses are not sent to `Exit`, because this
 * process does not terminate DNS-over-TLS. That is a loop/contract guard, not an
 * application firewall.
 */
class FirestackForwardingEngine : ForwardingEngine {
    private val connectionIds = AtomicLong()
    private val lock = Any()

    @Volatile private var tunnel: Tunnel? = null
    @Volatile private var request: ForwardingRequest? = null
    @Volatile private var protocolsById: MutableMap<String, Int> = HashMap()

    override fun start(request: ForwardingRequest) {
        synchronized(lock) {
            check(tunnel == null) { "Forwarding engine is already running" }
            this.request = request
            protocolsById = HashMap()
            Settings.setDebug(false)
            Settings.defaultTunMode()
            Settings.dupTunFd(true)
            Intra.setSELF_UID(android.os.Process.myUid().toString())
            val created = Intra.connect(
                request.tunFd.toLong(),
                request.linkMtu.toLong().coerceAtLeast(MIN_LINK_MTU),
                request.tunMtu.toLong(),
                request.interfaceAddresses,
                request.fakeDnsEndpoints,
                Intra.newBuiltinDefaultDNS(),
                EngineBridge(),
            )
            tunnel = created
            MonitorLog.info("firestack started ${Intra.build(false)} pin=${FirestackPin.COMMIT}")
        }
    }

    override fun stop() {
        val current = synchronized(lock) {
            val active = tunnel
            tunnel = null
            request = null
            protocolsById = HashMap()
            active
        }
        runCatching { current?.disconnect() }
            .onFailure { MonitorLog.error("firestack disconnect", it) }
    }

    override fun updateUnderlay(linkMtu: Int, dnsServers: List<String>) {
        val active = tunnel ?: return
        val mtu = linkMtu.toLong().coerceAtLeast(MIN_LINK_MTU)
        runCatching { active.setLinkMtu(mtu) }
            .onFailure { MonitorLog.error("link mtu", it) }
        val csv = dnsServers.filter { it.isNotBlank() }.joinToString(",")
        runCatching { Intra.setSystemDNS(active, csv) }
            .onSuccess { MonitorLog.info("system dns set ($csv)") }
            .onFailure { reportFault("DNS du réseau physique indisponible: ${it.message}") }
    }

    override fun nicBytes(): NicBytes? {
        val active = tunnel ?: return null
        return runCatching {
            val nic = active.stat().nic()
            NicBytes(
                received = nic.rx.toLongOrNull() ?: 0L,
                sent = nic.tx.toLongOrNull() ?: 0L,
            )
        }.onFailure { MonitorLog.error("nic stats", it) }.getOrNull()
    }

    private fun reportFault(message: String) {
        MonitorLog.error(message, null)
        runCatching { request?.listener?.onFault(message) }
    }

    private fun rememberProtocol(id: String, protocol: Int) {
        synchronized(lock) { protocolsById[id] = protocol }
    }

    private fun protocolFor(summary: FlowSummary): Int {
        val stored = synchronized(lock) { protocolsById[summary.id.orEmpty()] }
        if (stored != null) return stored
        return protocolNumber(summary.proto)
    }

    private inner class EngineBridge : Bridge {
        override fun protect(who: String?, fd: Long) {
            if (!protectFd(fd)) {
                MonitorLog.error("socket protect failed who=$who fd=$fd", null)
            }
        }

        override fun bind4(who: String, addrPort: String, fid: Long) = bind(who, addrPort, fid, 4)
        override fun bind6(who: String, addrPort: String, fid: Long) = bind(who, addrPort, fid, 6)

        private fun bind(who: String, addrPort: String, fid: Long, family: Int) {
            val protected = protectFd(fid)
            val guard = socketGuard
            val failure = if (guard == null) "socket guard missing" else guard.bind(fid.toInt())
            if (!protected || failure != null) {
                MonitorLog.error(
                    "socket bind family=$family who=$who remote=$addrPort protected=$protected failure=$failure",
                    null,
                )
            }
        }

        override fun preflow(protocol: Int, uid: Int, src: String?, dst: String?): PreMark {
            val injection = runCatching {
                request?.attributor?.attribute(protocol, src, dst)
            }.onFailure { MonitorLog.error("preflow", it) }.getOrNull()
            return PreMark().apply {
                this.uid = (injection?.uid ?: UNKNOWN_UID).toString()
                isUidSelf = injection?.self == true
            }
        }

        override fun flow(
            protocol: Int,
            uid: Int,
            src: String?,
            dst: String?,
            realIps: String?,
            domain: String?,
            probableDomains: String?,
            blocklists: String?,
            isAlg: Boolean,
        ): Mark {
            val id = "mon-${connectionIds.incrementAndGet()}"
            rememberProtocol(id, protocol)
            val active = request
            val proxy = proxyFor(active, protocol, uid, src, dst, domain)
            runCatching {
                active?.listener?.onFlowOpened(
                    ObservedFlow(
                        connectionId = id,
                        protocol = protocol,
                        uid = uid,
                        source = src,
                        destination = dst,
                        reportedDomain = domain,
                    ),
                )
            }.onFailure { MonitorLog.error("onFlowOpened", it) }
            return mark(id, uid, proxy)
        }

        override fun inflow(protocol: Int, uid: Int, src: String?, dst: String?): Mark {
            val id = "in-${connectionIds.incrementAndGet()}"
            rememberProtocol(id, protocol)
            return mark(id, uid, Backend.Exit)
        }

        override fun flowing(mark: Mark?) = Unit

        override fun postflow(summary: FlowSummary?) {
            if (summary == null) return
            val active = request ?: return
            val protocol = protocolFor(summary)
            val uid = summary.uid?.toIntOrNull() ?: UNKNOWN_UID
            runCatching {
                active.listener.onFlowClosed(
                    ClosedFlow(
                        connectionId = summary.id.orEmpty(),
                        protocol = protocol,
                        uid = uid,
                        source = summary.source,
                        destination = summary.target,
                        reportedDomain = null,
                        bytesReceived = summary.rx.coerceAtLeast(0),
                        bytesSent = summary.tx.coerceAtLeast(0),
                    ),
                )
            }.onFailure { MonitorLog.error("onFlowClosed", it) }
        }

        override fun onQuery(origin: String, uidGostr: String, fqdn: String, qtype: Long): DNSOpts {
            return DNSOpts().apply {
                uid = uidGostr
                ipcsv = ""
                tidcsv = Backend.System
                tidseccsv = ""
                noblock = true
            }
        }

        override fun onResponse(summary: DNSSummary?) {
            if (summary == null || summary.rCode != 0L) return
            if (summary.qType != 1L && summary.qType != 28L) return
            val answers = listOfNotNull(summary.rData, summary.targets)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (answers.isBlank() || summary.qName.isNullOrBlank()) return
            runCatching {
                request?.listener?.onDns(
                    ObservedDns(
                        name = summary.qName,
                        type = summary.qType,
                        rcode = summary.rCode,
                        ttlSeconds = summary.rTtl,
                        answers = answers,
                    ),
                )
            }.onFailure { MonitorLog.error("onDns", it) }
        }

        override fun onPrequery(a: String, b: String, c: String, qtype: Long): DomainOpts? = null

        override fun onUpstreamAnswer(
            id: String,
            summary: DNSSummary,
            opts: DNSOpts,
            ipcsv: String,
        ): DNSOpts = opts

        override fun svcRoute(
            sid: String,
            pid: String,
            network: String,
            sipport: String,
            dipport: String,
        ) = Tab()

        override fun onSvcComplete(summary: ServerSummary) = Unit
        override fun onDNSAdded(id: String?) = Unit
        override fun onDNSRemoved(id: String?) = Unit
        override fun onDNSStopped() = Unit
        override fun onProxiesStopped() = Unit
        override fun onProxyAdded(pid: String?, handle: String) = Unit
        override fun onProxyRemoved(pid: String?, handle: String) = Unit
        override fun onProxyStopped(pid: String?, handle: String) = Unit
        override fun onProxyUpdated(pid: String, handle: String) = Unit

        private fun proxyFor(
            active: ForwardingRequest?,
            protocol: Int,
            uid: Int,
            src: String?,
            dst: String?,
            domain: String?,
        ): String {
            val virtualPort = virtualDnsPort(dst)
            if (virtualPort != null) {
                return if (virtualPort == 53) Backend.Base else Backend.Block
            }
            val decision = active?.policy?.decide(
                PolicyRequest(protocol, uid, src, dst, domain),
            ) ?: PolicyDecision.ALLOW
            return when (decision) {
                PolicyDecision.ALLOW -> Backend.Exit
            }
        }

        private fun virtualDnsPort(destination: String?): Int? {
            val endpoint = EndpointParser.parse(destination) ?: return null
            val ips = request?.virtualDnsIps.orEmpty()
            if (endpoint.canonicalIp !in ips) return null
            return endpoint.port
        }

        private fun mark(id: String, uid: Int, proxy: String) = Mark().apply {
            pidcsv = proxy
            cid = id
            this.uid = uid.toString()
            ip = ""
        }
    }

    private fun protectFd(fd: Long): Boolean = socketGuard?.protect(fd.toInt()) == true

    @Volatile private var socketGuard: SocketGuard? = null

    fun setSocketGuard(guard: SocketGuard?) {
        socketGuard = guard
    }

    companion object {
        private const val MIN_LINK_MTU = 1280L
        private const val UNKNOWN_UID = -1

        fun protocolNumber(name: String?): Int = when (name?.trim()?.lowercase()) {
            "tcp", "6" -> 6
            "udp", "17" -> 17
            "icmp", "1" -> 1
            "icmpv6", "58" -> 58
            else -> name?.trim()?.toIntOrNull() ?: -1
        }
    }
}

/** VpnService operations the engine cannot perform itself. */
interface SocketGuard {
    fun protect(fd: Int): Boolean
    /** Null when the socket was bound; otherwise a short failure reason. */
    fun bind(fd: Int): String?
}
