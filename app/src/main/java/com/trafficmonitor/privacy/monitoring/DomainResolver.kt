package com.trafficmonitor.privacy.monitoring

import com.trafficmonitor.privacy.forwarding.EndpointParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporary DNS evidence. A name is attached to an IP only while its TTL is live
 * and it is the only live name for that IP. Conflicting names stay unresolved
 * so the UI does not invent a hostname.
 */
class DomainResolver(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Sighting(val domain: String, val expiresAt: Long)

    private val byIp = ConcurrentHashMap<String, MutableList<Sighting>>()

    fun observe(name: String?, ips: Collection<String>, ttlSeconds: Long) {
        val domain = EndpointParser.normalizeHostname(name) ?: return
        if (ttlSeconds <= 0) return
        val ttl = ttlSeconds.coerceIn(1, MAX_TTL_SECONDS)
        val expiresAt = now() + ttl * 1000
        val canonicalIps = ips.mapNotNull(EndpointParser::canonicalIp).distinct()
        if (canonicalIps.isEmpty()) return
        for (ip in canonicalIps) {
            val list = byIp.computeIfAbsent(ip) { mutableListOf() }
            synchronized(list) {
                list.removeAll { it.domain == domain || it.expiresAt <= now() }
                list.add(Sighting(domain, expiresAt))
            }
        }
    }

    fun lookup(canonicalIp: String): String? {
        val list = byIp[canonicalIp] ?: return null
        val live = synchronized(list) {
            list.removeAll { it.expiresAt <= now() }
            list.map { it.domain }.distinct()
        }
        return live.singleOrNull()
    }

    companion object {
        private const val MAX_TTL_SECONDS = 3600L
    }
}
