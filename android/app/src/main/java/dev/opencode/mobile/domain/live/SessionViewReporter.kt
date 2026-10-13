package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Acknowledges server read state (`POST /api/session/{id}/view`) for the session
 * open in Session Chat, mirroring the official TUI: only while focused, only when
 * the session is unread, once per idle watermark, with backoff on failure.
 * Servers without `supportsSessionViewed` never see a request.
 */
interface SessionViewReporter {
    fun focus(sessionId: String)

    /** Clears focus only while [sessionId] still owns it (same contract as Ready focus). */
    fun release(sessionId: String)

    /** Profile switch or sign-out: drop focus and remembered watermarks. */
    fun clearCache()
}

object NoOpSessionViewReporter : SessionViewReporter {
    override fun focus(sessionId: String) = Unit

    override fun release(sessionId: String) = Unit

    override fun clearCache() = Unit
}

@Singleton
class DefaultSessionViewReporter(
    private val sessionRepository: SessionRepository,
    private val sessionLiveStore: SessionLiveStore,
    private val eventStream: ForegroundEventStream,
    private val liveStoreScope: LiveStoreScope,
    private val capabilities: () -> ServerCapabilities = { ServerCapabilities.forProtocol(ServerProtocol.V1) },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : SessionViewReporter {
    @Inject
    constructor(
        sessionRepository: SessionRepository,
        sessionLiveStore: SessionLiveStore,
        eventStream: ForegroundEventStream,
        liveStoreScope: LiveStoreScope,
        activeConnectionManager: ActiveConnectionManager,
    ) : this(
        sessionRepository = sessionRepository,
        sessionLiveStore = sessionLiveStore,
        eventStream = eventStream,
        liveStoreScope = liveStoreScope,
        capabilities = { activeConnectionManager.currentCapabilities() },
    )

    private val lock = Any()
    private var focusedSessionId: String? = null
    private var focusJob: Job? = null
    private var triggers: Channel<Unit>? = null

    /** Idle watermark already acknowledged (or in flight) per session. */
    private val acknowledged = HashMap<String, Long>()

    override fun focus(sessionId: String) {
        synchronized(lock) {
            if (focusedSessionId == sessionId && focusJob?.isActive == true) {
                // Re-entering the same chat (nav return): re-check in case a run ended meanwhile.
                triggers?.trySend(Unit)
                return
            }
            focusJob?.cancel()
            val channel = Channel<Unit>(Channel.CONFLATED)
            channel.trySend(Unit)
            focusedSessionId = sessionId
            triggers = channel
            focusJob = liveStoreScope.coroutineScope.launch { runFocus(sessionId, channel) }
        }
    }

    override fun release(sessionId: String) {
        synchronized(lock) {
            if (focusedSessionId != sessionId) return
            focusJob?.cancel()
            focusJob = null
            triggers = null
            focusedSessionId = null
        }
    }

    override fun clearCache() {
        synchronized(lock) {
            focusJob?.cancel()
            focusJob = null
            triggers = null
            focusedSessionId = null
            acknowledged.clear()
        }
    }

    private suspend fun runFocus(
        sessionId: String,
        channel: Channel<Unit>,
    ) = coroutineScope {
        launch {
            eventStream.events.collect { event ->
                val relevant =
                    event.type in RECONNECT_EVENTS ||
                        (event.type in RUN_END_EVENTS && event.sessionId == sessionId)
                if (relevant) channel.trySend(Unit)
            }
        }
        launch {
            // A list refresh that moves the idle watermark (run finished while open).
            sessionLiveStore
                .observeKnownSession(sessionId)
                .map { it?.idleAtEpochMs }
                .distinctUntilChanged()
                .drop(1)
                .collect { channel.trySend(Unit) }
        }
        while (true) {
            channel.receive()
            acknowledgeWithRetry(sessionId)
        }
    }

    /**
     * One bounded burst per trigger: the first attempt plus up to [MAX_RETRY_ATTEMPTS]
     * retries (250 ms doubling, capped at [MAX_BACKOFF_MS]). After that it waits for the
     * next trigger (focus, run end, list idle change, reconnect) instead of polling.
     */
    private suspend fun acknowledgeWithRetry(sessionId: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        var retries = 0
        while (!acknowledgeOnce(sessionId)) {
            if (retries >= MAX_RETRY_ATTEMPTS) {
                log("view gave up session=$sessionId after $retries retries; waiting for next trigger")
                return
            }
            wait(backoffMs)
            retries += 1
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** True when nothing (more) needs posting; false when it should be retried. */
    private suspend fun acknowledgeOnce(sessionId: String): Boolean {
        if (!capabilities().supportsSessionViewed) return true
        val session =
            try {
                sessionRepository.getSession(sessionId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                log("view check failed session=$sessionId error=${error::class.simpleName}")
                return false
            }
        // Subagent children never appear on Home; only roots carry read state for the user.
        if (session.parentId != null) return true
        if (!session.isServerUnread) return true
        val idle = session.idleAtEpochMs ?: return true
        synchronized(lock) {
            if ((acknowledged[sessionId] ?: Long.MIN_VALUE) >= idle) return true
            acknowledged[sessionId] = idle
        }
        try {
            sessionRepository.markSessionViewed(sessionId, idle)
        } catch (cancel: CancellationException) {
            forget(sessionId, idle)
            throw cancel
        } catch (error: Throwable) {
            forget(sessionId, idle)
            log("view post failed session=$sessionId error=${error::class.simpleName}")
            return false
        }
        log("view posted session=$sessionId idle=$idle")
        sessionLiveStore.applySessionViewed(sessionId, idle)
        return true
    }

    private fun forget(
        sessionId: String,
        idle: Long,
    ) {
        synchronized(lock) {
            if (acknowledged[sessionId] == idle) acknowledged.remove(sessionId)
        }
    }

    private fun log(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Session, "sessionView $message")
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 250L
        const val MAX_BACKOFF_MS = 5_000L

        /** Retries after the first failed attempt within one trigger (250, 500, 1000, 2000, 4000 ms). */
        const val MAX_RETRY_ATTEMPTS = 5

        private val RECONNECT_EVENTS = setOf("server.connected", "stream.reconnected")

        /** Projected run end (`session.execution.*`); the server then moves `time.idle`. */
        private val RUN_END_EVENTS = setOf("session.idle")
    }
}
