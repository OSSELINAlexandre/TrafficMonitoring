package com.trafficmonitor.privacy.forwarding

/**
 * Local forwarding of the single Android TUN descriptor.
 *
 * The TUN is a stream, not a bus: this engine is the only reader. Monitoring observes
 * the structured events below. There is no second packet parser on the same fd, and
 * PCAP stays disabled so payloads are never written.
 *
 * V1 always forwards. [PolicyEngine] is the seam where V2 may later refuse a flow;
 * this milestone only defines [PolicyDecision.ALLOW].
 */
interface ForwardingEngine {
    fun start(request: ForwardingRequest)
    fun stop()
    fun updateUnderlay(linkMtu: Int, dnsServers: List<String>)
    fun nicBytes(): NicBytes?
}

data class ForwardingRequest(
    val tunFd: Int,
    val linkMtu: Int,
    val tunMtu: Int,
    val interfaceAddresses: String,
    val fakeDnsEndpoints: String,
    val virtualDnsIps: Set<String>,
    val attributor: FlowAttributor,
    val listener: ForwardingListener,
    val policy: PolicyEngine,
)

fun interface FlowAttributor {
    fun attribute(protocol: Int, source: String?, destination: String?): UidInjection
}

data class UidInjection(val uid: Int, val self: Boolean)

interface ForwardingListener {
    fun onFlowOpened(flow: ObservedFlow)
    fun onFlowClosed(flow: ClosedFlow)
    fun onDns(event: ObservedDns)
    fun onFault(message: String)
}

data class ObservedFlow(
    val connectionId: String,
    val protocol: Int,
    val uid: Int,
    val source: String?,
    val destination: String?,
    val reportedDomain: String?,
)

data class ClosedFlow(
    val connectionId: String,
    val protocol: Int,
    val uid: Int,
    val source: String?,
    val destination: String?,
    val reportedDomain: String?,
    val bytesReceived: Long,
    val bytesSent: Long,
)

data class ObservedDns(
    val name: String,
    val type: Long,
    val rcode: Long,
    val ttlSeconds: Long,
    val answers: String,
)

data class NicBytes(val received: Long, val sent: Long)

data class PolicyRequest(
    val protocol: Int,
    val uid: Int,
    val source: String?,
    val destination: String?,
    val reportedDomain: String?,
)

enum class PolicyDecision {
    ALLOW,
}

fun interface PolicyEngine {
    fun decide(request: PolicyRequest): PolicyDecision
}

class AllowAllPolicyEngine : PolicyEngine {
    override fun decide(request: PolicyRequest): PolicyDecision = PolicyDecision.ALLOW
}
