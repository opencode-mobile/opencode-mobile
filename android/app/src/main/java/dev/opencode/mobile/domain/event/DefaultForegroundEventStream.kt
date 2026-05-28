package dev.opencode.mobile.domain.event

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

        private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
        override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

        private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

        override fun start() {
            if (streamJob?.isActive == true) return
            streamJob =
                scope.launch {
                    var retryAttempt = 0
                    while (true) {
                        _connectionState.value =
                            if (wasConnected) EventStreamConnectionState.Reconnecting else EventStreamConnectionState.Connecting
                        try {
                            eventsApi.streamGlobalEvents { dataLine ->
                                val event = eventDomainAdapter.parseEvent(dataLine) ?: return@streamGlobalEvents
                                when (event.type) {
                                    "server.connected" -> {
                                        _connectionState.value = EventStreamConnectionState.Connected
                                        if (wasConnected) {
                                            _events.tryEmit(OpencodeEvent(type = "stream.reconnected"))
                                        }
                                        wasConnected = true
                                        retryAttempt = 0
                                    }
                                    "server.heartbeat" -> Unit
                                    else -> scope.launch { _events.emit(event) }
                                }
                            }
                            _connectionState.value = EventStreamConnectionState.Disconnected
                        } catch (_: CancellationException) {
                            _connectionState.value = EventStreamConnectionState.Idle
                            return@launch
                        } catch (_: Throwable) {
                            _connectionState.value =
                                if (wasConnected) EventStreamConnectionState.Reconnecting else EventStreamConnectionState.Disconnected
                        }

                        retryAttempt += 1
                        val backoffMs = (1_000L * retryAttempt).coerceAtMost(10_000L)
                        delay(backoffMs)
                    }
                }
        }

        override fun stop() {
            streamJob?.cancel()
            streamJob = null
            _connectionState.value = EventStreamConnectionState.Idle
        }
    }
