package dev.opencode.testguard

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The process-wide [ProxySelector] while unit tests run. It lets loopback through and sends every
 * other host to a closed loopback port, so the connection fails fast with a `ConnectException` and
 * never leaves the machine. Each blocked attempt goes to [onBlocked] with its scheme and host, never
 * the path, query or credentials.
 *
 * Every connection path our tests can reach asks the default selector: OkHttp (and with it Ktor's
 * OkHttp engine) for each call, `HttpURLConnection`, and a plain `java.net.Socket` (`socket://`
 * URIs). OkHttp and the JDK do not fall back to a direct connection when the proxy fails.
 */
internal class NetworkBlock(
    private val delegate: ProxySelector?,
    private val onBlocked: (scheme: String, host: String) -> Unit,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri" }
        val host = uri.host ?: uri.authority?.substringBefore(':').orEmpty()
        if (isLoopback(host)) {
            return delegate?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        onBlocked(scheme, host)
        // A plain Socket only honours SOCKS proxies; HTTP clients take either.
        val type = if (scheme == "socket") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return listOf(Proxy(type, deadEnd))
    }

    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        ioe: IOException?,
    ) {
        if (sa == deadEnd) return
        delegate?.connectFailed(uri, sa, ioe)
    }

    companion object {
        /** Port 9 (discard) on loopback: closed on developer machines and CI runners. */
        val deadEnd = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)

        private val loopbackNames = setOf("localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]")

        /** Decides from the literal only: resolving a name here would itself reach the network. */
        fun isLoopback(host: String): Boolean {
            val normalized = host.lowercase().trimEnd('.')
            if (normalized in loopbackNames || normalized.endsWith(".localhost")) return true
            val octets = normalized.split('.')
            return octets.size == 4 && octets[0] == "127" && octets.all { it.toIntOrNull() in 0..255 }
        }
    }
}
