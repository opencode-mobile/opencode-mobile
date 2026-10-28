package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class T3SocketState {
    Disconnected,
    Connecting,
    Connected,
}

/** Transport seam for [T3Runtime]; [T3WebSocket] is the OkHttp implementation. */
interface T3Socket {
    /** Lossless, in-order delivery of every parsed frame from the current socket. */
    val frames: Flow<T3RpcFrame>
    val state: StateFlow<T3SocketState>

    fun connect(url: String)

    suspend fun request(
        tag: String,
        payload: JsonObject,
    ): JsonElement

    /** Sends a streaming request and returns its id; chunks arrive on [frames]. */
    fun sendSubscribe(
        tag: String,
        payload: JsonObject,
    ): String

    fun disconnect()
}

@Singleton
class T3WebSocket
    @Inject
    constructor() : T3Socket {
        private val client =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()

        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

        /** Unbounded so a chunk is never dropped between ack and delivery. */
        private val frameChannel = Channel<T3RpcFrame>(Channel.UNLIMITED)
        override val frames: Flow<T3RpcFrame> = frameChannel.receiveAsFlow()

        private val _state = MutableStateFlow(T3SocketState.Disconnected)
        override val state: StateFlow<T3SocketState> = _state.asStateFlow()

        @Volatile
        private var socket: WebSocket? = null

        @Volatile
        private var generation = 0

        @Synchronized
        override fun connect(url: String) {
            generation += 1
            val id = generation
            socket?.cancel()
            failPending("T3 websocket replaced")
            _state.value = T3SocketState.Connecting
            val request = Request.Builder().url(url).build()
            socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            if (id != generation) return
                            DiagnosticLog.info(DiagnosticLogArea.Connection, "T3 socket open")
                            _state.value = T3SocketState.Connected
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (id != generation) return
                            handleText(webSocket, text)
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            if (id != generation) return
                            DiagnosticLog.info(DiagnosticLogArea.Connection, "T3 socket closed code=$code reason=$reason")
                            failPending("T3 websocket closed")
                            _state.value = T3SocketState.Disconnected
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (id != generation) return
                            DiagnosticLog.warning(
                                DiagnosticLogArea.Connection,
                                "T3 socket failed status=${response?.code ?: "-"}",
                                t,
                            )
                            failPending(t.message ?: "T3 websocket failed")
                            _state.value = T3SocketState.Disconnected
                        }
                    },
                )
        }

        override suspend fun request(
            tag: String,
            payload: JsonObject,
        ): JsonElement {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JsonElement>()
            pending[id] = deferred
            val sent = send(T3RpcCodec.encodeRequest(id, tag, payload))
            if (!sent) {
                pending.remove(id)
                throw T3ConnectionLostException("T3 websocket is not connected")
            }
            return try {
                deferred.await()
            } finally {
                pending.remove(id)
            }
        }

        override fun sendSubscribe(
            tag: String,
            payload: JsonObject,
        ): String {
            val id = UUID.randomUUID().toString()
            val sent = send(T3RpcCodec.encodeRequest(id, tag, payload))
            if (!sent) throw T3ConnectionLostException("T3 websocket is not connected")
            return id
        }

        @Synchronized
        override fun disconnect() {
            generation += 1
            socket?.close(1000, null)
            socket = null
            failPending("T3 websocket disconnected")
            _state.value = T3SocketState.Disconnected
        }

        private fun handleText(
            webSocket: WebSocket,
            text: String,
        ) {
            val frame =
                runCatching { T3RpcCodec.parse(text) }
                    .getOrElse { return }
            when (frame) {
                is T3RpcFrame.Ping -> webSocket.send(T3RpcCodec.encodePong())
                is T3RpcFrame.Chunk -> {
                    // Queue first, then ack: the unbounded channel never rejects, so
                    // the server only sees an ack for a chunk we hold.
                    frameChannel.trySend(frame)
                    webSocket.send(T3RpcCodec.encodeAck(frame.requestId))
                }
                is T3RpcFrame.ExitSuccess -> {
                    pending.remove(frame.requestId)?.complete(frame.value)
                    frameChannel.trySend(frame)
                }
                is T3RpcFrame.ExitFailure -> {
                    pending.remove(frame.requestId)?.completeExceptionally(T3RpcException.rejected(frame.cause))
                    frameChannel.trySend(frame)
                }
                else -> frameChannel.trySend(frame)
            }
        }

        private fun send(text: String): Boolean = socket?.send(text) == true

        private fun failPending(detail: String) {
            pending.values.forEach { deferred ->
                deferred.completeExceptionally(T3ConnectionLostException(detail))
            }
            pending.clear()
        }
    }
