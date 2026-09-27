package com.trafficmonitor.privacy.monitoring

import com.trafficmonitor.privacy.data.model.DomainSource
import com.trafficmonitor.privacy.forwarding.ClosedFlow
import com.trafficmonitor.privacy.forwarding.EndpointParser
import com.trafficmonitor.privacy.forwarding.ObservedDns
import com.trafficmonitor.privacy.forwarding.ObservedFlow

data class AggregateSnapshot(
    val uid: Int,
    val attribution: Attribution,
    val remoteIp: String,
    val domain: String?,
    val domainSource: DomainSource,
    val protocol: String,
    val remotePort: Int,
    val connectionCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
)

data class LiveCounts(
    val applications: Int = 0,
    val destinations: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
)

/**
 * In-memory flows for the active session. Rows are keyed by attribution, canonical
 * remote IP, protocol and port. The domain is evidence attached to that key, not
 * part of the key, so a late DNS answer does not split the counters.
 */
class FlowTracker(
    private val domains: DomainResolver,
    private val ignoredDestination: (String) -> Boolean = TunProfile::isVirtualDestination,
) {
    private val lock = Any()
    private val aggregates = LinkedHashMap<AggregateKey, Accumulator>()
    private val counted = HashSet<String>()
    private val closed = HashSet<String>()

    fun onDns(event: ObservedDns) {
        domains.observe(event.name, EndpointParser.ipLiterals(event.answers), event.ttlSeconds)
    }

    fun onFlowOpened(flow: ObservedFlow, attribution: Attribution) {
        synchronized(lock) {
            val key = key(flow.protocol, flow.destination, attribution) ?: return
            note(flow.connectionId, key, attribution, flow.reportedDomain)
        }
    }

    fun onFlowClosed(flow: ClosedFlow, attribution: Attribution) {
        synchronized(lock) {
            if (!closed.add(flow.connectionId.ifBlank { "close-${flow.hashCode()}" })) return
            val key = key(flow.protocol, flow.destination, attribution) ?: return
            val id = flow.connectionId.ifBlank { "close-${flow.hashCode()}" }
            val acc = note(id, key, attribution, flow.reportedDomain)
            acc.bytesReceived += flow.bytesReceived.coerceAtLeast(0)
            acc.bytesSent += flow.bytesSent.coerceAtLeast(0)
        }
    }

    fun snapshot(): List<AggregateSnapshot> = synchronized(lock) {
        aggregates.map { (key, acc) ->
            val reported = acc.reportedDomain
            val resolved = reported ?: domains.lookup(key.canonicalIp)
            AggregateSnapshot(
                uid = key.uid,
                attribution = acc.attribution,
                remoteIp = key.canonicalIp,
                domain = resolved,
                domainSource = if (resolved == null) DomainSource.UNKNOWN else DomainSource.DNS_OBSERVED,
                protocol = key.protocol,
                remotePort = key.remotePort,
                connectionCount = acc.connectionCount,
                bytesSent = acc.bytesSent,
                bytesReceived = acc.bytesReceived,
            )
        }
    }

    fun liveCounts(): LiveCounts = synchronized(lock) {
        val rows = snapshotUnsafe()
        LiveCounts(
            applications = rows.map { it.uid to it.attribution.packageCsv() }.distinct().size,
            destinations = rows.map { it.domain ?: it.remoteIp }.distinct().size,
            bytesSent = rows.sumOf { it.bytesSent },
            bytesReceived = rows.sumOf { it.bytesReceived },
        )
    }

    fun reset() {
        synchronized(lock) {
            aggregates.clear()
            counted.clear()
            closed.clear()
        }
    }

    private fun snapshotUnsafe(): List<AggregateSnapshot> {
        return aggregates.map { (key, acc) ->
            val resolved = acc.reportedDomain ?: domains.lookup(key.canonicalIp)
            AggregateSnapshot(
                uid = key.uid,
                attribution = acc.attribution,
                remoteIp = key.canonicalIp,
                domain = resolved,
                domainSource = if (resolved == null) DomainSource.UNKNOWN else DomainSource.DNS_OBSERVED,
                protocol = key.protocol,
                remotePort = key.remotePort,
                connectionCount = acc.connectionCount,
                bytesSent = acc.bytesSent,
                bytesReceived = acc.bytesReceived,
            )
        }
    }

    private fun note(
        connectionId: String,
        key: AggregateKey,
        attribution: Attribution,
        reportedDomain: String?,
    ): Accumulator {
        val acc = aggregates.getOrPut(key) { Accumulator(attribution) }
        if (acc.attribution.label == Attribution.LABEL_UNKNOWN && attribution.label != Attribution.LABEL_UNKNOWN) {
            acc.attribution = attribution
        }
        val hostname = EndpointParser.normalizeHostname(reportedDomain)
        if (hostname != null) acc.reportedDomain = hostname
        val id = connectionId.ifBlank { "open-$key" }
        if (counted.add(id)) acc.connectionCount += 1
        return acc
    }

    private fun key(protocol: Int, destination: String?, attribution: Attribution): AggregateKey? {
        val endpoint = EndpointParser.parse(destination) ?: return null
        if (ignoredDestination(endpoint.canonicalIp)) return null
        return AggregateKey(
            uid = attribution.uid,
            packageCsv = attribution.packageCsv(),
            canonicalIp = endpoint.canonicalIp,
            protocol = Protocols.name(protocol),
            remotePort = endpoint.port,
        )
    }

    private data class AggregateKey(
        val uid: Int,
        val packageCsv: String,
        val canonicalIp: String,
        val protocol: String,
        val remotePort: Int,
    )

    private class Accumulator(var attribution: Attribution) {
        var reportedDomain: String? = null
        var connectionCount: Int = 0
        var bytesSent: Long = 0
        var bytesReceived: Long = 0
    }
}
