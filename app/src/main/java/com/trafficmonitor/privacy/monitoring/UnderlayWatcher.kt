package com.trafficmonitor.privacy.monitoring

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.trafficmonitor.privacy.data.model.UnderlayType

data class UnderlaySnapshot(
    val network: Network,
    val wifi: Boolean,
    val cellular: Boolean,
    val mtu: Int,
    val dnsServers: List<String>,
) {
    fun kind(): String = UnderlayPreference.kind(wifi, cellular)
}

/**
 * Chooses the non-VPN underlay used for socket bind, `setUnderlyingNetworks`,
 * and system DNS. Weights match the Firestack spike: a validated Wi-Fi wins
 * when it is present; otherwise a validated mobile network beats an
 * unvalidated Wi-Fi. A later change only retargets that underlay. The VPN
 * is not recreated.
 */
object UnderlayPreference {
    fun score(validated: Boolean, wifi: Boolean, cellular: Boolean): Int {
        var value = 0
        if (validated) value += 100
        if (wifi) value += 10
        if (cellular) value += 5
        return value
    }

    fun kind(wifi: Boolean, cellular: Boolean): String = when {
        wifi -> UnderlayType.WIFI
        cellular -> UnderlayType.CELLULAR
        else -> UnderlayType.OTHER
    }
}

class UnderlayWatcher(
    private val connectivity: ConnectivityManager,
    private val onSnapshot: (UnderlaySnapshot) -> Unit,
) {
    private val candidates = mutableSetOf<Network>()
    private val gate = Any()

    @Volatile var current: Network? = null
        private set

    @Volatile var latest: UnderlaySnapshot? = null
        private set

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(gate) { candidates += network }
            select()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(gate) { candidates += network }
            select()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == current) publish(network)
        }

        override fun onLost(network: Network) {
            synchronized(gate) { candidates -= network }
            if (network == current) {
                current = null
                select()
            }
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        registered = true
        // Existing matching networks are delivered through onAvailable.
        // activeNetwork still covers the gap before that callback arrives.
        select()
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun recordedKind(): String = latest?.kind() ?: UnderlayType.UNKNOWN

    private fun select() {
        val chosen = preferred() ?: return
        if (chosen == current && latest != null) return
        current = chosen
        publish(chosen)
    }

    private fun publish(network: Network) {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        if (!eligible(network)) return
        val link = connectivity.getLinkProperties(network)
        val snapshot = UnderlaySnapshot(
            network = network,
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            mtu = link?.mtu?.takeIf { it > 0 } ?: DEFAULT_MTU,
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
        )
        latest = snapshot
        onSnapshot(snapshot)
    }

    private fun preferred(): Network? {
        val pool = synchronized(gate) { candidates.toMutableSet() }
        connectivity.activeNetwork?.let { pool += it }
        val ranked = pool.filter(::eligible)
        if (ranked.isEmpty()) return null
        val staying = current
        val active = connectivity.activeNetwork
        return ranked.maxWithOrNull(
            compareBy<Network>(::score)
                .thenBy { network -> if (network == staying) 1 else 0 }
                .thenBy { network -> if (network == active) 1 else 0 },
        )
    }

    private fun eligible(network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun score(network: Network): Int {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return 0
        return UnderlayPreference.score(
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }

    companion object {
        const val DEFAULT_MTU = 1500
    }
}
