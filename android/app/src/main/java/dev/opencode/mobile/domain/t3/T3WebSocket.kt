package dev.opencode.mobile.domain.t3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

@Singleton
class T3WebSocket
    @Inject
    constructor() {
        private val client =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()

        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
        private val _frames = MutableSharedFlow<T3RpcFrame>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val frames: SharedFlow<T3RpcFrame> = _frames.asSharedFlow()

        private val _state = MutableStateFlow(T3SocketState.Disconnected)
        val state: StateFlow<T3SocketState> = _state.asStateFlow()

        @Volatile
        private var socket: WebSocket? = null

        @Volatile
        private var generation = 0

        @Synchronized
        fun connect(url: String) {
            generation += 1
            val id = generation
            socket?.cancel()
            pending.values.forEach { it.cancel() }
            pending.clear()
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
                            failPending("T3 websocket closed")
                            _state.value = T3SocketState.Disconnected
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (id != generation) return
                            failPending(t.message ?: "T3 websocket failed")
                            _state.value = T3SocketState.Disconnected
                        }
                    },
                )
        }

        suspend fun request(
            tag: String,
            payload: JsonObject,
        ): JsonElement {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JsonElement>()
            pending[id] = deferred
            val sent = send(T3RpcCodec.encodeRequest(id, tag, payload))
            if (!sent) {
                pending.remove(id)
                throw T3RpcException("T3 websocket is not connected")
            }
            return try {
                deferred.await()
            } finally {
                pending.remove(id)
            }
        }

        fun sendSubscribe(
            tag: String,
            payload: JsonObject,
        ): String {
            val id = UUID.randomUUID().toString()
            val sent = send(T3RpcCodec.encodeRequest(id, tag, payload))
            if (!sent) throw T3RpcException("T3 websocket is not connected")
            return id
        }

        fun disconnect() {
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
                    webSocket.send(T3RpcCodec.encodeAck(frame.requestId))
                    _frames.tryEmit(frame)
                }
                is T3RpcFrame.ExitSuccess -> {
                    pending.remove(frame.requestId)?.complete(frame.value)
                    _frames.tryEmit(frame)
                }
                is T3RpcFrame.ExitFailure -> {
                    pending.remove(frame.requestId)?.completeExceptionally(T3RpcException(frame.cause.toString()))
                    _frames.tryEmit(frame)
                }
                else -> _frames.tryEmit(frame)
            }
        }

        private fun send(text: String): Boolean = socket?.send(text) == true

        private fun failPending(message: String) {
            pending.values.forEach { deferred ->
                deferred.completeExceptionally(T3RpcException(message))
            }
            pending.clear()
        }
    }
