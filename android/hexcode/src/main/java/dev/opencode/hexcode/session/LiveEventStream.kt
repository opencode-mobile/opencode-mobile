package dev.opencode.hexcode.session

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.domain.event.SseAttemptPolicy
import dev.opencode.mobile.domain.event.SseRetryDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** One connection to `GET /api/event` with [SessionEvent.TYPES]; [EventsApiTransport] in the app. */
fun interface EventTransport {
    /**
     * Streams until the connection ends. [onActivity] runs for every line, heartbeats included; [onLine] for each
     * `data:` payload.
     */
    suspend fun connect(
        onActivity: () -> Unit,
        onLine: suspend (String) -> Unit,
    ): SseAttemptOutcome
}

/**
 * The event stream with OpenCode Mobile's reconnect timing (app-plan.md, Today's session on screen): after a drop it
 * retries after 1, 2, 4 and 8 s, then every 8 s, with a little jitter ([SseAttemptPolicy]); [wake] retries at once.
 * More than [stallMs] with no data at all, not even the server's 15 s heartbeat, counts as a drop.
 *
 * [onConnected] runs when the server's first frame arrives, before any later event is read, so the caller can read
 * the state back first.
 */
class LiveEventStream(
    private val scope: CoroutineScope,
    private val transport: EventTransport,
    private val stallMs: Long = STALL_MS,
    private val random: Random = Random.Default,
) {
    var onConnected: suspend () -> Unit = {}
    var onDisconnected: () -> Unit = {}
    var onEvent: (SessionEvent) -> Unit = {}

    private var loop: Job? = null
    private var attemptJob: Job? = null
    private val wakes = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var attempt = 0

    @Volatile
    private var holdMs = 0L

    val isRunning: Boolean get() = loop?.isActive == true

    /** Connects, and keeps reconnecting until [stop]. */
    fun start() {
        if (isRunning) return
        attempt = 0
        loop = scope.launch { run() }
    }

    /** Closes the stream (in the background, or when another session takes over). */
    fun stop() {
        loop?.cancel()
        loop = null
        attemptJob = null
        onDisconnected()
    }

    /** Back in front or the network returned: retry now instead of waiting out the backoff. */
    fun wake() {
        attempt = 0
        wakes.trySend(Unit)
    }

    /** Developer settings' "Drop event stream": as if the network dropped, and held down for [forMs]. */
    fun drop(forMs: Long = DROP_HOLD_MS) {
        holdMs = forMs
        attemptJob?.cancel(CancellationException(DROPPED))
    }

    private suspend fun run() {
        while (scope.isActive) {
            attempt += 1
            val outcome = connectOnce()
            onDisconnected()
            val hold = holdMs
            if (hold > 0) {
                holdMs = 0
                attempt = 0
                delay(hold)
                continue
            }
            val wait =
                when (val decision = SseAttemptPolicy.decide(outcome, protocolConfirmed = true, attempt = attempt, random = random)) {
                    is SseRetryDecision.Retry -> decision.delayMs
                    is SseRetryDecision.AuthFailed -> decision.delayMs
                }
            withTimeoutOrNull(wait) { wakes.receive() }
        }
    }

    /** One attempt, ended by the server, the network, a stall or a drop. */
    private suspend fun connectOnce(): SseAttemptOutcome =
        coroutineScope {
            var stalled = false
            var watchdog: Job? = null
            lateinit var connection: Job
            var outcome: SseAttemptOutcome = SseAttemptOutcome.Ended
            val restartWatchdog = {
                watchdog?.cancel()
                watchdog =
                    launch {
                        delay(stallMs)
                        stalled = true
                        connection.cancel(CancellationException(STALLED))
                    }
            }
            connection =
                launch {
                    outcome =
                        try {
                            transport.connect(onActivity = { restartWatchdog() }) { line ->
                                val event = SessionEvent.decode(line) ?: return@connect
                                if (event == SessionEvent.Connected) {
                                    attempt = 0
                                    onConnected()
                                } else {
                                    onEvent(event)
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            SseAttemptOutcome.Transport(error)
                        }
                }
            attemptJob = connection
            restartWatchdog()
            connection.join()
            watchdog?.cancel()
            attemptJob = null
            if (connection.isCancelled) {
                // Cancelled by the watchdog or a drop, not by the stream's own scope (that would have thrown here).
                outcome = SseAttemptOutcome.Transport(if (stalled) IllegalStateException(STALLED) else null)
            }
            outcome
        }

    companion object {
        /** Data, or the 15 s heartbeat, must arrive within this. */
        const val STALL_MS = 35_000L

        /** How long "Drop event stream" holds the stream down. */
        const val DROP_HOLD_MS = 5_000L

        private const val STALLED = "No data from the event stream for 35 s"
        private const val DROPPED = "Event stream dropped from developer settings"
    }
}
