package dev.opencode.mobile.domain.connection

import java.net.URI

/**
 * Where plain `http` is acceptable without asking.
 *
 * Cleartext to loopback, private, link-local, and Tailscale ranges (plus names that only
 * resolve on such networks) connects silently. Any other `http` host carries the server
 * password unencrypted across networks the user does not control, so the app warns and
 * asks for confirmation first. `https` and `wss` are always fine.
 *
 * Android's network security config cannot express IP ranges, so this policy is the
 * enforcement point; `network_security_config.xml` only documents that.
 */
object CleartextPolicy {
    enum class Verdict {
        Encrypted,
        TrustedCleartext,
        UntrustedCleartext,
    }

    /** mDNS, Tailscale MagicDNS, and the common private-DNS suffixes. */
    val trustedNameSuffixes: List<String> = listOf("local", "ts.net", "lan", "internal", "home.arpa")

    const val WARNING_TITLE = "Connection isn't encrypted"

    fun warningMessage(hosts: List<String>): String {
        val subject = if (hosts.isEmpty()) "This address" else hosts.joinToString(", ")
        val verb = if (hosts.size > 1) "use" else "uses"
        return "$subject $verb plain http outside your local network or Tailscale. " +
            "Your server password and everything in your sessions would travel unencrypted. " +
            "Prefer https, a VPN, or a tunnel."
    }

    fun verdict(raw: String): Verdict {
        val uri = parse(raw) ?: return Verdict.UntrustedCleartext
        val scheme = uri.scheme?.lowercase() ?: "http"
        if (scheme == "https" || scheme == "wss") return Verdict.Encrypted
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Verdict.UntrustedCleartext
        return if (isTrustedCleartextHost(host)) Verdict.TrustedCleartext else Verdict.UntrustedCleartext
    }

    /** Hosts (deduplicated, in candidate order) that would need the warning. */
    fun untrustedCleartextHosts(urls: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in urls) {
            if (verdict(raw) != Verdict.UntrustedCleartext) continue
            val uri = parse(raw)
            val host = uri?.host?.lowercase() ?: raw.trim()
            val label = if (uri != null && uri.port != -1) "$host:${uri.port}" else host
            seen += label
        }
        return seen.toList()
    }

    fun isTrustedCleartextHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".localhost")) return true
        ipv4Octets(host)?.let { return isTrustedIpv4(it) }
        if (host.contains(':')) return isTrustedIpv6(host)
        // Names that only resolve on the user's own network or tailnet.
        if (!host.contains('.')) return true
        return trustedNameSuffixes.any { host == it || host.endsWith(".$it") }
    }

    private fun parse(raw: String): URI? {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        return runCatching { URI(withScheme) }.getOrNull()
    }

    private fun ipv4Octets(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets
    }

    private fun isTrustedIpv4(o: List<Int>): Boolean =
        when {
            o[0] == 127 -> true // loopback
            o[0] == 10 -> true // RFC 1918
            o[0] == 172 && o[1] in 16..31 -> true // RFC 1918
            o[0] == 192 && o[1] == 168 -> true // RFC 1918
            o[0] == 169 && o[1] == 254 -> true // link-local
            o[0] == 100 && o[1] in 64..127 -> true // CGNAT, Tailscale IPv4
            else -> false
        }

    private fun isTrustedIpv6(host: String): Boolean {
        val bare = host.substringBefore('%')
        if (bare == "::1") return true // loopback
        if (bare.startsWith("fe8") || bare.startsWith("fe9") || bare.startsWith("fea") || bare.startsWith("feb")) {
            return true // link-local fe80::/10
        }
        if (bare.startsWith("fd7a:115c:a1e0:")) return true // Tailscale
        return bare.startsWith("fc") || bare.startsWith("fd") // ULA fc00::/7
    }
}
