package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.message.ConnectionEpoch
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultForegroundEventStream
    @Inject
    constructor(
        private val eventsApi: EventsApi,
        private val eventDomainAdapter: EventDomainAdapter,
        private val diagnosticLogger: DiagnosticLogger,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : ForegroundEventStream {
        private val scope = CoroutineScope(SupervisorJob())
        private var streamJob: Job? = null

        /** Invalidates in-flight cancel/error handlers after stop() or a newer start(). */
        private val streamGeneration = AtomicLong(0)
        private var wasConnected = false
        private var currentStreamWasConnected = false
        private var emittedReconnectForCurrentStream = false

        private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
        override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

        private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 4_096)
        override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

        override fun start() {
            if (streamJob?.isActive == true) {
                diagnosticLogger.debug(DiagnosticLogArea.Sse, "foreground SSE start ignored; already running")
                return
            }
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream starting")
            val generation = streamGeneration.incrementAndGet()
            ConnectionEpoch.shared.bump()
            streamJob =
                scope.launch {
                    var retryAttempt = 0
                    while (true) {
                        if (generation != streamGeneration.get()) return@launch
                        currentStreamWasConnected = false
                        emittedReconnectForCurrentStream = false
                        val previouslyConnected = wasConnected
                        setConnectionState(
                            if (previouslyConnected) {
                                EventStreamConnectionState.Reconnecting
                            } else {
                                EventStreamConnectionState.Connecting
                            },
                            generation,
                        )
                        try {
                            val protocol = activeConnectionManager.protocolHint()
                            launch { activeConnectionManager.currentProtocolOrProbe() }
                            val path = if (protocol == ServerProtocol.V2) "/api/event" else "/global/event"
                            eventsApi.streamGlobalEvents(
                                protocol = protocol,
                                onHTTPResponse = { statusCode ->
                                    diagnosticLogger.info(
                                        DiagnosticLogArea.Sse,
                                        "GET $path opened status=$statusCode protocol=${protocol.wireValue}",
                                    )
                                    markStreamConnected()
                                    retryAttempt = 0
                                },
                            ) { dataLine ->
                                val parsed = eventDomainAdapter.parseEvents(dataLine)
                                if (parsed.isEmpty()) {
                                    diagnosticLogger.warning(DiagnosticLogArea.Sse, "SSE event parse failed")
                                    return@streamGlobalEvents
                                }
                                for (event in parsed) {
                                    diagnosticLogger.debug(
                                        DiagnosticLogArea.Sse,
                                        "SSE event type=${event.type} session=${event.sessionId ?: "-"}",
                                    )
                                    when (event.type) {
                                        "server.connected" -> {
                                            markStreamConnected()
                                            retryAttempt = 0
                                            _events.emit(event)
                                        }
                                        "server.heartbeat" -> Unit
                                        else -> {
                                            markStreamConnected()
                                            // Emit on the read coroutine so order is preserved and
                                            // we do not spawn one job per delta under burst.
                                            _events.emit(event)
                                        }
                                    }
                                }
                            }
                            // Stream ended while still occupied — keep chrome on connecting /
                            // reconnecting through the retry sleep (OPE-165). Do not publish
                            // offline (Disconnected) while a foreground retry is in flight.
                            setConnectionState(
                                if (previouslyConnected || wasConnected) {
                                    EventStreamConnectionState.Reconnecting
                                } else {
                                    EventStreamConnectionState.Connecting
                                },
                                generation,
                            )
                        } catch (_: CancellationException) {
                            if (generation == streamGeneration.get()) {
                                streamJob = null
                                setConnectionState(EventStreamConnectionState.Idle, generation)
                            }
                            return@launch
                        } catch (throwable: Throwable) {
                            diagnosticLogger.warning(
                                DiagnosticLogArea.Sse,
                                "foreground SSE stream failed",
                                throwable,
                            )
                            setConnectionState(
                                if (previouslyConnected || wasConnected) {
                                    EventStreamConnectionState.Reconnecting
                                } else {
                                    EventStreamConnectionState.Connecting
                                },
                                generation,
                            )
                        }

                        retryAttempt += 1
                        val backoffMs = (1_000L * retryAttempt).coerceAtMost(10_000L)
                        diagnosticLogger.debug(DiagnosticLogArea.Sse, "foreground SSE retry in ${backoffMs}ms")
                        delay(backoffMs)
                    }
                }
        }

        override fun stop() {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream stopping")
            streamGeneration.incrementAndGet()
            streamJob?.cancel()
            streamJob = null
            wasConnected = false
            currentStreamWasConnected = false
            emittedReconnectForCurrentStream = false
            setConnectionState(EventStreamConnectionState.Idle)
        }

        private suspend fun markStreamConnected() {
            val shouldPublishReconnect = wasConnected && !currentStreamWasConnected && !emittedReconnectForCurrentStream
            if (_connectionState.value != EventStreamConnectionState.Connected) {
                setConnectionState(EventStreamConnectionState.Connected)
            }
            if (shouldPublishReconnect) {
                diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream reconnected")
                ConnectionEpoch.shared.bump()
                _events.emit(OpencodeEvent(type = "stream.reconnected"))
                emittedReconnectForCurrentStream = true
            }
            currentStreamWasConnected = true
            wasConnected = true
        }

        private fun setConnectionState(
            state: EventStreamConnectionState,
            generation: Long? = null,
        ) {
            if (generation != null && generation != streamGeneration.get()) return
            if (_connectionState.value != state) {
                diagnosticLogger.debug(
                    DiagnosticLogArea.Sse,
                    "foreground SSE state ${_connectionState.value} -> $state",
                )
            }
            _connectionState.value = state
        }
    }
