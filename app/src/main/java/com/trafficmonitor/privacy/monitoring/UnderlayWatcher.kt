package com.trafficmonitor.privacy.monitoring

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

data class UnderlaySnapshot(
    val network: Network,
    val wifi: Boolean,
    val cellular: Boolean,
    val mtu: Int,
    val dnsServers: List<String>,
)

/**
 * Picks a non-VPN network that already has Internet. The active network wins so
 * traffic keeps using whatever the device is using (mobile data or Wi-Fi).
 * Mid-session handoff is best-effort only.
 */
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
        connectivity.activeNetwork?.takeIf(::eligible)?.let { return it }
        val pool = synchronized(gate) { candidates.toList() }
        return pool.filter(::eligible).maxByOrNull(::score)
    }

    private fun eligible(network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun score(network: Network): Int {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return 0
        var value = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) value += 100
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) value += 5
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) value += 4
        return value
    }

    companion object {
        const val DEFAULT_MTU = 1500
    }
}
