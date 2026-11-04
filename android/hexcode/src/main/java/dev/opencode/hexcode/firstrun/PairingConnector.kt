package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.connection.CleartextPolicy
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.PairingCodeRedeemException
import dev.opencode.mobile.domain.connection.PairingCodeRedeeming
import dev.opencode.mobile.domain.connection.PairingQRError
import dev.opencode.mobile.domain.connection.PairingQRParseResult
import dev.opencode.mobile.domain.connection.PairingQRPayload
import dev.opencode.mobile.domain.connection.ServerAddressProbe
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/** The server checks that run behind the scan screen. They are only shown when one fails. */
enum class ServerCheck {
    /** The code is a pairing code with an address the phone may use. */
    Code,

    /** One of the code's addresses answered. */
    Reach,

    /** The one-use code was accepted and gave the phone its password. */
    Pair,

    /** OpenCode 2.2.5 or later, on the v2 API. */
    Version,

    /** The routes Hex Code uses are there. */
    Routes,
}

/** Why connecting stopped, at the check that failed. */
sealed interface ConnectFailure {
    val check: ServerCheck

    data object NotAPairingCode : ConnectFailure {
        override val check = ServerCheck.Code
    }

    /** Every address in the code is on this phone (`localhost`). */
    data object OnlyLoopback : ConnectFailure {
        override val check = ServerCheck.Code
    }

    /** Every address is plain `http` outside the LAN, Tailscale and loopback. */
    data class NotEncrypted(
        val host: String,
    ) : ConnectFailure {
        override val check = ServerCheck.Code
    }

    data class Unreachable(
        val host: String,
    ) : ConnectFailure {
        override val check = ServerCheck.Reach
    }

    data object CodeUsed : ConnectFailure {
        override val check = ServerCheck.Pair
    }

    data class Rejected(
        val host: String,
    ) : ConnectFailure {
        override val check = ServerCheck.Pair
    }

    /** The server answered, but it is not an OpenCode 2 server. */
    data object NotOpenCode2 : ConnectFailure {
        override val check = ServerCheck.Version
    }

    /** Everything passed, but the phone couldn't keep the password (Keystore or storage failure). */
    data object NotSaved : ConnectFailure {
        override val check = ServerCheck.Routes
    }

    data class TooOld(
        val version: String?,
    ) : ConnectFailure {
        override val check = ServerCheck.Version
    }

    data class MissingRoutes(
        val routes: List<String>,
    ) : ConnectFailure {
        override val check = ServerCheck.Routes
    }
}

sealed interface ConnectResult {
    data class Connected(
        val profile: ConnectionProfile,
        /** The computer's friendly name ([ServerName]). */
        val name: String,
        /** Its own hostname from `/api/info`, when it gave one. */
        val hostname: String?,
        val version: String?,
    ) : ConnectResult

    data class Failed(
        val failure: ConnectFailure,
    ) : ConnectResult
}

/** What the scan screen shows while connecting: the computer has no name until its hostname is known. */
sealed interface ConnectProgress {
    data object Connecting : ConnectProgress
}

/** Reads the server's API contract for the routes Hex Code needs; returns the missing ones. */
fun interface ServerRouteCheck {
    suspend fun missingRoutes(connection: ServerConnection): List<String>
}

/** The name the computer gives itself (`hostname` in `/api/info`), or null when it doesn't say. */
fun interface ServerNaming {
    suspend fun name(connection: ServerConnection): String?
}

/**
 * Turns a scanned pairing code into a saved, checked server, the way OpenCode Mobile pairs:
 *
 * 1. Every address in the code gets one health request without credentials; one is chosen (Tailscale first).
 * 2. The one-use code is redeemed on that host only, for the password every later request carries.
 * 3. The server must be OpenCode 2.2.5+ on the v2 API, with the routes Hex Code uses.
 *
 * Nothing is saved unless every check passes. Plain `http` is used only on the LAN, Tailscale and loopback
 * ([CleartextPolicy]); Hex Code has no "connect anyway".
 */
class PairingConnector(
    private val probing: ConnectionProbing,
    private val redeeming: PairingCodeRedeeming,
    private val routes: ServerRouteCheck,
    private val naming: ServerNaming = ServerNaming { null },
) {
    suspend fun connect(
        raw: String,
        onProgress: (ConnectProgress) -> Unit = {},
    ): ConnectResult {
        val credentials =
            when (val parsed = PairingQRPayload.parse(raw)) {
                is PairingQRParseResult.Ready -> parsed.credentials
                is PairingQRParseResult.Failed ->
                    return failed(
                        when (parsed.error) {
                            PairingQRError.InvalidPayload -> ConnectFailure.NotAPairingCode
                            PairingQRError.NoReachableUrl -> ConnectFailure.OnlyLoopback
                        },
                    )
            }
        val candidates = ConnectionProfileIdentity.normalizeServerUrls(credentials.urls)
        val urls = candidates.filterNot { CleartextPolicy.verdict(it) == CleartextPolicy.Verdict.UntrustedCleartext }
        if (urls.isEmpty()) return failed(ConnectFailure.NotEncrypted(hostLabel(candidates.firstOrNull() ?: credentials.baseUrl)))
        val shownHost = hostLabel(PairingQRPayload.preferredDisplayUrl(urls))
        onProgress(ConnectProgress.Connecting)
        val username = credentials.username
        val code = credentials.code
        try {
            val winner: String
            val password: String
            if (code != null && credentials.password.isEmpty()) {
                // No password yet: the sweep is unauthenticated and the code is redeemed on one host only.
                val sweep = ServerAddressProbe.resolve(urls, username, password = "", currentBaseUrl = null, probing = probing)
                winner = sweep.chosen ?: return failed(ConnectFailure.Unreachable(shownHost))
                password =
                    try {
                        redeeming.redeem(winner, code)
                    } catch (_: PairingCodeRedeemException.Expired) {
                        return failed(ConnectFailure.CodeUsed)
                    } catch (_: PairingCodeRedeemException.Unreachable) {
                        return failed(ConnectFailure.Unreachable(hostLabel(winner)))
                    }
            } else {
                val sweep = ServerAddressProbe.resolve(urls, username, credentials.password, currentBaseUrl = null, probing = probing)
                winner = sweep.chosen ?: return failed(ConnectFailure.Unreachable(shownHost))
                if (sweep.failure?.isAuthRejection() == true) return failed(ConnectFailure.Rejected(hostLabel(winner)))
                password = credentials.password
            }
            val connection = ServerConnection(baseUrl = winner, username = username, password = password)
            val server =
                try {
                    probing.probeServer(connection)
                } catch (error: ServerProbeError) {
                    return failed(
                        if (error.isAuthRejection()) {
                            ConnectFailure.Rejected(
                                hostLabel(winner),
                            )
                        } else {
                            ConnectFailure.Unreachable(hostLabel(winner))
                        },
                    )
                }
            if (server.protocol != ServerProtocol.V2) return failed(ConnectFailure.NotOpenCode2)
            if (!ServerVersion.isSupported(server.version)) return failed(ConnectFailure.TooOld(server.version))
            val missing = routes.missingRoutes(connection.withProtocol(ServerProtocol.V2))
            if (missing.isNotEmpty()) return failed(ConnectFailure.MissingRoutes(missing))
            val hostname =
                runCatching { naming.name(connection.withProtocol(ServerProtocol.V2)) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            // The profile keeps the hostname, so the friendly name can be worked out again later.
            val profile =
                ConnectionProfile(
                    baseUrl = winner,
                    username = username,
                    password = password,
                    serverProtocol = ServerProtocol.V2,
                    name = hostname.orEmpty(),
                    serverUrls = urls,
                )
            return ConnectResult.Connected(profile, ServerName.of(hostname, credentials.urls.first()), hostname, server.version)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failed(ConnectFailure.Unreachable(shownHost))
        }
    }

    private fun failed(failure: ConnectFailure) = ConnectResult.Failed(failure)

    private fun ServerProbeError.isAuthRejection(): Boolean = this is ServerProbeError.Http && (statusCode == 401 || statusCode == 403)

    companion object {
        /** `mbp.local` for `http://mbp.local:4096/`: what the screen names the computer by. */
        fun hostLabel(url: String): String =
            runCatching { URI(url.trim()).host }
                .getOrNull()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.takeIf { it.isNotBlank() }
                ?: ConnectionProfileIdentity.hostLabel(url)
    }
}

/** OpenCode releases Hex Code runs on. */
object ServerVersion {
    val MINIMUM = listOf(2, 2, 5)

    /**
     * True for 2.2.5 and later. Source and preview builds report `local` or `0.0.0-<channel>-<n>`; those pass here and
     * the route check decides.
     */
    fun isSupported(version: String?): Boolean {
        val parts =
            version
                ?.trim()
                ?.removePrefix("v")
                ?.substringBefore('-')
                ?.split('.')
                ?.map { it.toIntOrNull() } ?: return true
        if (parts.size < 3 || parts.any { it == null }) return true
        val numbers = parts.filterNotNull().take(3)
        if (numbers == listOf(0, 0, 0)) return true
        for (i in 0 until 3) {
            if (numbers[i] != MINIMUM[i]) return numbers[i] > MINIMUM[i]
        }
        return true
    }
}
