package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Picks one address out of a profile's saved list without spraying the password.
 *
 * 1. Every candidate gets one health request **without credentials**.
 * 2. One candidate is chosen among those that answered (Tailscale first, never an
 *    `http` address when the current base URL is `https`).
 * 3. Only the chosen host receives the password. A `401` there is an authentication
 *    failure, not a reachable server; no other host is tried afterwards.
 *
 * Both server protocols keep their health endpoints behind Basic auth, so a live
 * opencode answers the unauthenticated request with `401`. That makes the host a
 * *candidate*; it never makes it *reachable*.
 */
object ServerAddressProbe {
    /** How a candidate answered the unauthenticated health request. */
    sealed interface Answer {
        val answered: Boolean
            get() = this !is Silent

        /** Health answered without credentials (server running without a password). */
        data class Open(
            val protocol: ServerProtocol,
        ) : Answer

        /** The host demanded credentials. */
        data object Challenged : Answer

        /** Transport failure, non-auth HTTP error, or an unhealthy server. */
        data object Silent : Answer
    }

    data class Resolution(
        /** Unauthenticated answer per normalized candidate, in candidate order. */
        val answers: List<Pair<String, Answer>>,
        /** The single host that received credentials. `null` when nothing answered. */
        val chosen: String?,
        /**
         * Authenticated outcome for [chosen]. With nothing chosen it is `null`, except for a
         * single silent candidate, whose failure is kept for the error message.
         */
        val outcome: Result<ServerProtocol>?,
    ) {
        val answered: List<String>
            get() = answers.filter { it.second.answered }.map { it.first }

        /** Normalized candidates that did not answer at all. */
        val unreachable: Set<String>
            get() =
                answers
                    .filterNot { it.second.answered }
                    .map { ConnectionProfileIdentity.normalizeBaseUrl(it.first) }
                    .toSet()

        val reachableProtocol: ServerProtocol?
            get() = outcome?.getOrNull()

        val failure: ServerProbeError?
            get() {
                val error = outcome?.exceptionOrNull() ?: return null
                return error as? ServerProbeError ?: ServerProbeError.Transport(error)
            }

        val nothingAnswered: Boolean
            get() = chosen == null
    }

    /** Full resolution: unauthenticated sweep, one choice, credentials to that host only. */
    suspend fun resolve(
        urls: List<String>,
        username: String,
        password: String,
        currentBaseUrl: String?,
        probing: ConnectionProbing,
    ): Resolution {
        val unique = ConnectionProfileIdentity.normalizeServerUrls(urls)
        if (unique.size == 1) {
            // One candidate is the chosen host by definition: a single request, with credentials.
            // The sweep exists to pick among several hosts, not to add a round trip here.
            val only = unique.single()
            PairingQRPayload.pickReachableUrl(unique, currentBaseUrl)
                ?: return Resolution(answers = emptyList(), chosen = null, outcome = null)
            val outcome = probeAuthenticated(only, username, password, probing)
            val answer = answerFrom(outcome)
            // A host that never answered is not chosen, exactly as in the multi-candidate sweep;
            // its failure still travels so the caller can show why.
            return Resolution(listOf(only to answer), chosen = only.takeIf { answer.answered }, outcome = outcome)
        }
        val answers = probeUnauthenticated(urls, username, probing)
        val answered = answers.filter { it.second.answered }.map { it.first }
        val chosen =
            PairingQRPayload.pickReachableUrl(answered, currentBaseUrl)
                ?: return Resolution(answers, chosen = null, outcome = null)
        val unauthenticated = answers.firstOrNull { it.first == chosen }?.second ?: Answer.Silent
        if (password.isEmpty()) {
            // Nothing more to send: the unauthenticated request already was the real one.
            return when (unauthenticated) {
                is Answer.Open -> Resolution(answers, chosen, Result.success(unauthenticated.protocol))
                Answer.Challenged, Answer.Silent ->
                    Resolution(answers, chosen, Result.failure(ServerProbeError.Http(401, "Unauthorized")))
            }
        }
        return Resolution(answers, chosen, probeAuthenticated(chosen, username, password, probing))
    }

    /** Health request to every candidate with no `Authorization` header. */
    suspend fun probeUnauthenticated(
        urls: List<String>,
        username: String,
        probing: ConnectionProbing,
    ): List<Pair<String, Answer>> {
        val unique = ConnectionProfileIdentity.normalizeServerUrls(urls)
        return coroutineScope {
            unique
                .map { url ->
                    async {
                        val connection =
                            ServerConnection.fromProfile(
                                ConnectionProfile(baseUrl = url, username = username, password = ""),
                            )
                        url to
                            try {
                                Answer.Open(probing.probe(connection))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: ServerProbeError) {
                                if (error.toProbeStatus() == ProbeStatus.AuthFailed) Answer.Challenged else Answer.Silent
                            } catch (_: Throwable) {
                                Answer.Silent
                            }
                    }
                }.awaitAll()
        }
    }

    private fun answerFrom(outcome: Result<ServerProtocol>): Answer {
        outcome.getOrNull()?.let { return Answer.Open(it) }
        val error = outcome.exceptionOrNull() as? ServerProbeError
        return if (error?.toProbeStatus() == ProbeStatus.AuthFailed) Answer.Challenged else Answer.Silent
    }

    /** Authenticated health request to exactly one host. */
    suspend fun probeAuthenticated(
        url: String,
        username: String,
        password: String,
        probing: ConnectionProbing,
    ): Result<ServerProtocol> {
        val connection =
            ServerConnection.fromProfile(
                ConnectionProfile(baseUrl = url, username = username, password = password),
            )
        return try {
            Result.success(probing.probe(connection))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
