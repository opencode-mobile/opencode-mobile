package dev.opencode.mobile.domain.event

import android.util.Log
import dev.opencode.mobile.data.remote.EventsApi
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultForegroundEventStream
    @Inject
    constructor(
        private val eventsApi: EventsApi,
        private val eventDomainAdapter: EventDomainAdapter,
    ) : ForegroundEventStream {
        private val scope = CoroutineScope(SupervisorJob())
        private var streamJob: Job? = null
        private var wasConnected = false
        private var currentStreamWasConnected = false
        private var emittedReconnectForCurrentStream = false

        private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
        override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

        private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 4_096)
        override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

        override fun start() {
            if (streamJob?.isActive == true) return
            logI("foreground SSE stream starting")
            streamJob =
                scope.launch {
                    var retryAttempt = 0
                    while (true) {
                        currentStreamWasConnected = false
                        emittedReconnectForCurrentStream = false
                        setConnectionState(
                            if (wasConnected) EventStreamConnectionState.Reconnecting else EventStreamConnectionState.Connecting,
                        )
                        try {
                            eventsApi.streamGlobalEvents(
                                onHTTPResponse = { statusCode ->
                                    logI("GET /global/event opened status=$statusCode")
                                    markStreamConnected()
                                    retryAttempt = 0
                                },
                            ) { dataLine ->
                                val event =
                                    eventDomainAdapter.parseEvent(dataLine)
                                        ?: run {
                                            logW("SSE event parse failed")
                                            return@streamGlobalEvents
                                        }
                                logD("SSE event type=${event.type} session=${event.sessionId ?: "-"}")
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
                            setConnectionState(EventStreamConnectionState.Disconnected)
                        } catch (_: CancellationException) {
                            setConnectionState(EventStreamConnectionState.Idle)
                            return@launch
                        } catch (throwable: Throwable) {
                            logW("foreground SSE stream failed", throwable)
                            setConnectionState(
                                if (wasConnected) EventStreamConnectionState.Reconnecting else EventStreamConnectionState.Disconnected,
                            )
                        }

                        retryAttempt += 1
                        val backoffMs = (1_000L * retryAttempt).coerceAtMost(10_000L)
                        logD("foreground SSE retry in ${backoffMs}ms")
                        delay(backoffMs)
                    }
                }
        }

        override fun stop() {
            logI("foreground SSE stream stopping")
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
                logI("foreground SSE stream reconnected")
                _events.emit(OpencodeEvent(type = "stream.reconnected"))
                emittedReconnectForCurrentStream = true
            }
            currentStreamWasConnected = true
            wasConnected = true
        }

        private fun setConnectionState(state: EventStreamConnectionState) {
            if (_connectionState.value != state) {
                logD("foreground SSE state ${_connectionState.value} -> $state")
            }
            _connectionState.value = state
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logD(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logW(
            message: String,
            throwable: Throwable? = null,
        ) {
            runCatching {
                if (throwable == null) {
                    Log.w(TAG, message)
                } else {
                    Log.w(TAG, message, throwable)
                }
            }
        }

        private companion object {
            private const val TAG = "OpencodeSse"
        }
    }
