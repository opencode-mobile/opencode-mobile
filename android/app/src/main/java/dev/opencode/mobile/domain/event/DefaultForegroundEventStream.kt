package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.connection.ReadyDeviceNetworkPath
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
        private val connectionProfileStore: ConnectionProfileStore,
        private val deviceNetworkPath: DeviceNetworkPath = ReadyDeviceNetworkPath,
    ) : ForegroundEventStream {
        internal constructor(
            eventsApi: EventsApi,
            eventDomainAdapter: EventDomainAdapter,
            diagnosticLogger: DiagnosticLogger,
            activeConnectionManager: ActiveConnectionManager,
            connectionProfileStore: ConnectionProfileStore,
            deviceNetworkPath: DeviceNetworkPath = ReadyDeviceNetworkPath,
            idleTimeoutMs: Long,
        ) : this(
            eventsApi,
            eventDomainAdapter,
            diagnosticLogger,
            activeConnectionManager,
            connectionProfileStore,
            deviceNetworkPath,
        ) {
            this.idleTimeoutMs = idleTimeoutMs
        }

        private val scope = CoroutineScope(SupervisorJob())
        private var streamJob: Job? = null
        private var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
        private val eventDeduper = SseEventDeduper()

        /** Invalidates in-flight cancel/error handlers after stop() or a newer start(). */
        private val streamGeneration = AtomicLong(0)
        private var wasConnected = false
        private var currentStreamWasConnected = false
        private var emittedReconnectForCurrentStream = false

        /** Survives stop() so cold resume still emits stream.reconnected. */
        private var pendingResumeBarrier = false

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
                    try {
                        while (true) {
                            if (generation != streamGeneration.get()) return@launch
                            deviceNetworkPath.waitUntilReady()
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
                            val lastActivityMs = AtomicLong(System.currentTimeMillis())
                            lateinit var readJob: Job
                            readJob =
                                launch {
                                    try {
                                        val protocol = activeConnectionManager.protocolHint()
                                        launch {
                                            val resolved = activeConnectionManager.currentProtocolOrProbe()
                                            if (resolved != protocol) {
                                                diagnosticLogger.info(
                                                    DiagnosticLogArea.Sse,
                                                    "SSE protocol resolved ${protocol.wireValue} -> ${resolved.wireValue}; reconnecting",
                                                )
                                                readJob.cancel()
                                            }
                                        }
                                        val targets =
                                            SseSubscribeTargets.forProtocol(
                                                protocol,
                                                instanceDirectories(),
                                            )
                                        val emittedConnected = AtomicBoolean(false)
                                        coroutineScope {
                                            for (target in targets) {
                                                launch {
                                                    val path = "/${target.path}"
                                                    eventsApi.streamEvents(
                                                        path = target.path,
                                                        directory = target.directory,
                                                        onHTTPResponse = { statusCode ->
                                                            lastActivityMs.set(System.currentTimeMillis())
                                                            diagnosticLogger.info(
                                                                DiagnosticLogArea.Sse,
                                                                "GET $path opened status=$statusCode protocol=${protocol.wireValue}",
                                                            )
                                                            markStreamConnected()
                                                            retryAttempt = 0
                                                        },
                                                        onActivity = {
                                                            lastActivityMs.set(System.currentTimeMillis())
                                                        },
                                                    ) { dataLine ->
                                                        lastActivityMs.set(System.currentTimeMillis())
                                                        val parsed = eventDomainAdapter.parseEvents(dataLine)
                                                        if (parsed.isEmpty()) {
                                                            diagnosticLogger.warning(
                                                                DiagnosticLogArea.Sse,
                                                                "SSE event parse failed",
                                                            )
                                                            return@streamEvents
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
                                                                    if (emittedConnected.compareAndSet(false, true)) {
                                                                        _events.emit(event)
                                                                    }
                                                                }
                                                                "server.heartbeat" -> Unit
                                                                else -> {
                                                                    if (eventDeduper.accept(event.id)) {
                                                                        markStreamConnected()
                                                                        _events.emit(event)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        setConnectionState(
                                            if (previouslyConnected || wasConnected) {
                                                EventStreamConnectionState.Reconnecting
                                            } else {
                                                EventStreamConnectionState.Connecting
                                            },
                                            generation,
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
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
                                }
                            val watchJob =
                                launch {
                                    deviceNetworkPath.status.collect { status ->
                                        if (status == DeviceNetworkPathStatus.Unavailable) {
                                            readJob.cancel()
                                        }
                                    }
                                }
                            val idleJob =
                                launch {
                                    while (true) {
                                        val remainingMs =
                                            idleTimeoutMs - (System.currentTimeMillis() - lastActivityMs.get())
                                        if (remainingMs <= 0L) {
                                            diagnosticLogger.warning(
                                                DiagnosticLogArea.Sse,
                                                "foreground SSE idle timeout; reconnecting",
                                            )
                                            readJob.cancel()
                                            return@launch
                                        }
                                        delay(remainingMs.coerceAtMost(idleTimeoutMs))
                                    }
                                }
                            try {
                                readJob.join()
                            } finally {
                                idleJob.cancel()
                                watchJob.cancel()
                            }
                            if (deviceNetworkPath.status.value == DeviceNetworkPathStatus.Unavailable) {
                                setConnectionState(
                                    if (previouslyConnected || wasConnected) {
                                        EventStreamConnectionState.Reconnecting
                                    } else {
                                        EventStreamConnectionState.Connecting
                                    },
                                    generation,
                                )
                                continue
                            }

                            retryAttempt += 1
                            val backoffMs = (1_000L * retryAttempt).coerceAtMost(10_000L)
                            diagnosticLogger.debug(DiagnosticLogArea.Sse, "foreground SSE retry in ${backoffMs}ms")
                            delay(backoffMs)
                        }
                    } catch (_: CancellationException) {
                        if (generation == streamGeneration.get()) {
                            streamJob = null
                            setConnectionState(EventStreamConnectionState.Idle, generation)
                        }
                    }
                }
        }

        override fun stop() {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream stopping")
            if (wasConnected || currentStreamWasConnected) {
                pendingResumeBarrier = true
            }
            streamGeneration.incrementAndGet()
            streamJob?.cancel()
            streamJob = null
            eventDeduper.clear()
            wasConnected = false
            currentStreamWasConnected = false
            emittedReconnectForCurrentStream = false
            setConnectionState(EventStreamConnectionState.Idle)
        }

        private suspend fun markStreamConnected() {
            val warmReconnect =
                wasConnected && !currentStreamWasConnected && !emittedReconnectForCurrentStream
            val coldResume =
                pendingResumeBarrier && !currentStreamWasConnected && !emittedReconnectForCurrentStream
            val shouldPublishReconnect = warmReconnect || coldResume
            if (_connectionState.value != EventStreamConnectionState.Connected) {
                setConnectionState(EventStreamConnectionState.Connected)
            }
            if (shouldPublishReconnect) {
                diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream reconnected")
                ConnectionEpoch.shared.bump()
                _events.emit(OpencodeEvent(type = "stream.reconnected"))
                emittedReconnectForCurrentStream = true
                pendingResumeBarrier = false
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

        private suspend fun instanceDirectories(): List<String> {
            val baseUrl = activeConnectionManager.current()?.baseUrl ?: return emptyList()
            return runCatching { connectionProfileStore.addedWorktrees(baseUrl) }.getOrDefault(emptyList())
        }

        private companion object {
            const val DEFAULT_IDLE_TIMEOUT_MS = 45_000L
        }
    }
