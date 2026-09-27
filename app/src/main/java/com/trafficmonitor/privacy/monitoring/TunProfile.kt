package com.trafficmonitor.privacy.monitoring

import com.trafficmonitor.privacy.forwarding.EndpointParser

/** Addresses installed on the local monitoring TUN. Not remote destinations. */
object TunProfile {
    const val IPV4_ADDRESS = "10.111.222.1"
    const val IPV4_PREFIX = 24
    const val IPV6_ADDRESS = "fd66:f83a:c650::1"
    const val IPV6_PREFIX = 120
    const val DNS_IPV4 = "10.111.222.3"
    const val DNS_IPV6 = "fd66:f83a:c650::3"
    const val TUN_MTU = 1500

    fun interfaceAddresses(): String = "$IPV4_ADDRESS/$IPV4_PREFIX,$IPV6_ADDRESS/$IPV6_PREFIX"

    /** Firestack rejects bare fake-DNS addresses; they must be AddrPort values. */
    fun fakeDnsEndpoints(): String = "$DNS_IPV4:53,[$DNS_IPV6]:53"

    fun virtualDnsIps(): Set<String> = setOfNotNull(
        EndpointParser.canonicalIp(DNS_IPV4),
        EndpointParser.canonicalIp(DNS_IPV6),
    )

    fun isVirtualDestination(canonicalIp: String): Boolean {
        val virtual = virtualDnsIps() + setOfNotNull(
            EndpointParser.canonicalIp(IPV4_ADDRESS),
            EndpointParser.canonicalIp(IPV6_ADDRESS),
        )
        return canonicalIp in virtual
    }
}
