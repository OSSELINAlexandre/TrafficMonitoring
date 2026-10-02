package com.trafficmonitor.privacy.monitoring

object Protocols {
    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1
    const val ICMPV6 = 58

    fun name(protocol: Int): String = when (protocol) {
        TCP -> "TCP"
        UDP -> "UDP"
        ICMP -> "ICMP"
        ICMPV6 -> "ICMPv6"
        else -> "IP-$protocol"
    }

    fun supportsOwnerLookup(protocol: Int): Boolean = protocol == TCP || protocol == UDP
}
