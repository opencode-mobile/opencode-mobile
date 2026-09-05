package dev.opencode.mobile.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.terminal.TerminalError
import dev.opencode.mobile.domain.terminal.TerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalSession
import dev.opencode.mobile.domain.terminal.TerminalSessionStatus
import dev.opencode.mobile.domain.terminal.TerminalSocketClient
import dev.opencode.mobile.domain.terminal.TerminalSocketListener
import dev.opencode.mobile.domain.terminal.TerminalWsFrame
import dev.opencode.mobile.domain.terminal.TerminalWsFrameParser
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TerminalUiState {
    data object Connecting : TerminalUiState

    data class Live(
        val session: TerminalSession,
    ) : TerminalUiState

    data class Reconnecting(
        val session: TerminalSession,
    ) : TerminalUiState

    data object ConnectFailed : TerminalUiState

    data object Exited : TerminalUiState
}

@HiltViewModel
class TerminalViewModel
    @Inject
    constructor(
        private val terminalRepository: TerminalRepository,
        private val activeConnectionManager: ActiveConnectionManager,
        private val socketClient: TerminalSocketClient,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<TerminalUiState>(TerminalUiState.Connecting)
        val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

        private val _output =
            MutableSharedFlow<String>(
                extraBufferCapacity = 1024,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val output: SharedFlow<String> = _output.asSharedFlow()

        private var worktree: String? = null
        private var requestedPtyId: String? = null
        private var session: TerminalSession? = null
        private var cursor: String = "0"
        private var connectJob: Job? = null
        private var reconnectJob: Job? = null
        private var resizeJob: Job? = null
        private var leaving = false
        private var socketOpenedOnce = false
        private var reconnectAttempt = 0

        private val socketListener =
            object : TerminalSocketListener {
                override fun onOpen() {
                    viewModelScope.launch { handleOpen() }
                }

                override fun onText(text: String) {
                    val frame = TerminalWsFrameParser.parseText(text)
                    if (frame is TerminalWsFrame.Output) {
                        _output.tryEmit(frame.text)
                    }
                }

                override fun onBinary(bytes: ByteArray) {
                    val frame = TerminalWsFrameParser.parseBinary(bytes)
                    if (frame is TerminalWsFrame.Cursor) {
                        cursor = frame.value
                    }
                }

                override fun onClosed(code: Int) {
                    reconnectJob?.cancel()
                    reconnectJob = viewModelScope.launch { handleClosed(code) }
                }
            }

        fun appear(
            worktree: String,
            ptyId: String? = null,
        ) {
            leaving = false
            this.worktree = worktree
            requestedPtyId = ptyId
            val current = _uiState.value
            connect(resetToConnecting = current !is TerminalUiState.Live && current !is TerminalUiState.Reconnecting)
        }

        fun retry() {
            leaving = false
            if (_uiState.value is TerminalUiState.Exited) {
                cursor = "0"
                session = null
                requestedPtyId = null
                socketOpenedOnce = false
            }
            connect(resetToConnecting = true)
        }

        fun disappear() {
            leaving = true
            connectJob?.cancel()
            reconnectJob?.cancel()
            resizeJob?.cancel()
            socketClient.close()
        }

        fun sendInput(text: String) {
            if (text.isEmpty()) return
            socketClient.sendText(text)
        }

        override fun onCleared() {
            disappear()
            super.onCleared()
        }

        fun resize(
            cols: Int,
            rows: Int,
        ) {
            if (cols <= 0 || rows <= 0) return
            resizeJob?.cancel()
            resizeJob =
                viewModelScope.launch {
                    delay(RESIZE_DEBOUNCE_MS)
                    val running = session ?: return@launch
                    val directory = worktree ?: return@launch
                    runCatching {
                        terminalRepository.updateSize(running.id, directory, cols, rows)
                    }
                }
        }

        private fun connect(resetToConnecting: Boolean) {
            val directory = worktree ?: return
            connectJob?.cancel()
            reconnectJob?.cancel()
            socketClient.close()
            if (resetToConnecting) {
                _uiState.value = TerminalUiState.Connecting
            }
            connectJob =
                viewModelScope.launch {
                    try {
                        val ptyId = requestedPtyId
                        val ensured =
                            if (ptyId != null) {
                                terminalRepository.get(ptyId, directory)
                            } else {
                                terminalRepository.ensureRunning(directory)
                            }
                        session = ensured
                        if (ensured.status != TerminalSessionStatus.Running) {
                            _uiState.value = TerminalUiState.Exited
                            return@launch
                        }
                        openSocket(ensured)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _uiState.value = TerminalUiState.ConnectFailed
                    }
                }
        }

        private fun openSocket(session: TerminalSession) {
            val connection = activeConnectionManager.current()
            if (connection == null) {
                _uiState.value = TerminalUiState.ConnectFailed
                return
            }
            val protocol = connection.serverProtocol ?: ServerProtocol.V1
            val directory = worktree ?: return
            val request =
                terminalRepository.connectRequest(
                    id = session.id,
                    directory = directory,
                    protocol = protocol,
                    baseUrl = connection.baseUrl,
                    cursor = cursor,
                    ticket = null,
                    username = connection.username,
                    password = connection.password.orEmpty(),
                )
            socketClient.open(request.url, socketListener, request.authorization)
        }

        private fun handleOpen() {
            if (leaving) return
            val current = session ?: return
            socketOpenedOnce = true
            reconnectAttempt = 0
            _uiState.value = TerminalUiState.Live(current)
        }

        private suspend fun handleClosed(code: Int) {
            if (leaving || code == NORMAL_CLOSE_CODE) return
            val directory = worktree ?: return
            val current = session ?: return
            try {
                val latest = terminalRepository.get(current.id, directory)
                if (leaving) return
                session = latest
                if (latest.status == TerminalSessionStatus.Exited) {
                    _uiState.value = TerminalUiState.Exited
                    return
                }
                _uiState.value = TerminalUiState.Reconnecting(latest)
                delay(reconnectDelayMs())
                if (leaving) return
                openSocket(latest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TerminalError.Http) {
                if (error.statusCode == 404) {
                    _uiState.value = TerminalUiState.Exited
                } else {
                    retryAfterGetFailure(current)
                }
            } catch (_: Exception) {
                retryAfterGetFailure(current)
            }
        }

        private suspend fun retryAfterGetFailure(current: TerminalSession) {
            if (!socketOpenedOnce) {
                _uiState.value = TerminalUiState.ConnectFailed
                return
            }
            _uiState.value = TerminalUiState.Reconnecting(current)
            delay(reconnectDelayMs())
            if (leaving) return
            openSocket(current)
        }

        private fun reconnectDelayMs(): Long {
            val delayMs = (250L * (1 shl reconnectAttempt.coerceAtMost(4))).coerceAtMost(3_000L)
            reconnectAttempt += 1
            return delayMs
        }

        companion object {
            private const val NORMAL_CLOSE_CODE = 1000
            private const val RESIZE_DEBOUNCE_MS = 100L
        }
    }
