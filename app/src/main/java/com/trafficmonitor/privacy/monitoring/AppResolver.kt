package com.trafficmonitor.privacy.monitoring

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import com.trafficmonitor.privacy.data.model.AttributionStatus
import com.trafficmonitor.privacy.forwarding.EndpointParser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a flow to an Android UID while the original socket still exists.
 * The result is cached for the session; it is not recomputed per packet.
 */
class AppResolver(
    private val connectivity: ConnectivityManager,
    private val packageManager: PackageManager,
    private val selfUid: Int = Process.myUid(),
) {
    private val cache = ConcurrentHashMap<Int, Attribution>()

    fun attribute(protocol: Int, source: String?, destination: String?): Attribution {
        if (!Protocols.supportsOwnerLookup(protocol)) {
            return Attribution(
                uid = Attribution.UNKNOWN_UID,
                status = AttributionStatus.UNSUPPORTED_PROTOCOL,
                packages = emptyList(),
                label = Attribution.LABEL_UNKNOWN,
            )
        }
        val local = socket(source) ?: return Attribution.unknown()
        val remote = socket(destination) ?: return Attribution.unknown()
        var uid = owner(protocol, local, remote)
        if (uid == Process.INVALID_UID && protocol == Protocols.UDP) {
            uid = owner(protocol, local, InetSocketAddress(remote.address, 0))
        }
        if (uid == Process.INVALID_UID && protocol == Protocols.UDP) {
            val any = if (remote.address.address.size == 16) {
                InetAddress.getByName("::")
            } else {
                InetAddress.getByName("0.0.0.0")
            }
            uid = owner(protocol, local, InetSocketAddress(any, 0))
        }
        val attribution = identityForUid(uid)
        cache[uid] = attribution
        return attribution
    }

    fun identityForUid(uid: Int): Attribution {
        if (uid < 0) return Attribution.unknown(uid)
        return cache.computeIfAbsent(uid) { resolved ->
            val packages = packageManager.getPackagesForUid(resolved)?.toList().orEmpty().sorted()
            val status = AttributionRules.status(resolved, packages, selfUid)
            Attribution(
                uid = resolved,
                status = status,
                packages = packages,
                label = label(status, packages),
            )
        }
    }

    private fun label(status: AttributionStatus, packages: List<String>): String = when (status) {
        AttributionStatus.UNKNOWN, AttributionStatus.UNSUPPORTED_PROTOCOL -> Attribution.LABEL_UNKNOWN
        AttributionStatus.SYSTEM -> Attribution.LABEL_SYSTEM
        else -> packages.map(::applicationLabel).distinct().joinToString(", ").ifBlank {
            Attribution.LABEL_UNKNOWN
        }
    }

    private fun applicationLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        packageManager.getApplicationLabel(info)?.toString().orEmpty().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun owner(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int = try {
        connectivity.getConnectionOwnerUid(protocol, local, remote)
    } catch (error: Throwable) {
        MonitorLog.error("owner uid", error)
        Process.INVALID_UID
    }

    private fun socket(value: String?): InetSocketAddress? {
        val endpoint = EndpointParser.parse(value) ?: return null
        val host = value?.let(::hostOf) ?: return null
        return try {
            InetSocketAddress(InetAddress.getByName(host.substringBefore('%')), endpoint.port)
        } catch (_: Exception) {
            null
        }
    }

    private fun hostOf(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.startsWith("[")) {
            val closing = trimmed.indexOf(']')
            if (closing <= 1) return null
            return trimmed.substring(1, closing)
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        return trimmed.substring(0, colon)
    }
}
