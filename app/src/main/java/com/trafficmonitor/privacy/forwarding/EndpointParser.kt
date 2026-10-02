package com.trafficmonitor.privacy.forwarding

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class ParsedEndpoint(
    val canonicalIp: String,
    val port: Int,
)

/**
 * Parses Firestack endpoint strings (`ip:port` or `[ipv6]:port`).
 * Hostnames are rejected so a label is never turned into an address by DNS.
 */
object EndpointParser {
    private val ipv4 = Regex("""^(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    fun parse(value: String?): ParsedEndpoint? {
        if (value.isNullOrBlank()) return null
        val (host, portText) = splitHostPort(value.trim()) ?: return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 0..65535) return null
        val ip = canonicalIp(host) ?: return null
        return ParsedEndpoint(ip, port)
    }

    fun canonicalIp(host: String): String? {
        val clean = host.substringBefore('%').trim()
        if (!isIpLiteral(clean)) return null
        val address = try {
            InetAddress.getByName(clean)
        } catch (_: Exception) {
            return null
        }
        val textual = address.hostAddress?.substringBefore('%')?.lowercase() ?: return null
        return when (address) {
            is Inet4Address, is Inet6Address -> textual
            else -> null
        }
    }

    fun isIpLiteral(value: String): Boolean {
        val clean = value.substringBefore('%').trim()
        if (ipv4.matches(clean)) return true
        if (!clean.contains(':')) return false
        if (clean.count { it == '%' } > 0) return false
        if (!clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
            return false
        }
        if (clean.split("::").size > 2) return false
        return try {
            val address = InetAddress.getByName(clean)
            address is Inet4Address || address is Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A display hostname. IP literals and empty or single-label values are not domains.
     */
    fun normalizeHostname(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val host = value.trim().trimEnd('.').lowercase()
        if (host.length !in 1..253 || !host.contains('.')) return null
        if (host.startsWith('.') || host.contains("..")) return null
        if (isIpLiteral(host)) return null
        val labels = host.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    label.startsWith('-') || label.endsWith('-') ||
                    label.any { it != '-' && !it.isLetterOrDigit() }
            }
        ) {
            return null
        }
        return host
    }

    fun ipLiterals(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        val token = Regex("""[\[\],\s;]+""")
        text.split(token).forEach { raw ->
            val candidate = raw.trim().trimEnd('.')
            if (candidate.isEmpty()) return@forEach
            canonicalIp(candidate)?.let(found::add)
        }
        // Answers sometimes embed an address inside a longer token.
        Regex("""(?:\d{1,3}\.){3}\d{1,3}""").findAll(text).forEach { match ->
            canonicalIp(match.value)?.let(found::add)
        }
        Regex("""(?i)(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}""").findAll(text).forEach { match ->
            canonicalIp(match.value)?.let(found::add)
        }
        return found.toList()
    }

    private fun splitHostPort(value: String): Pair<String, String>? {
        if (value.startsWith("[")) {
            val closing = value.indexOf(']')
            if (closing <= 1 || value.getOrNull(closing + 1) != ':') return null
            return value.substring(1, closing) to value.substring(closing + 2)
        }
        val colon = value.lastIndexOf(':')
        if (colon <= 0 || colon == value.lastIndex) return null
        return value.substring(0, colon) to value.substring(colon + 1)
    }
}
