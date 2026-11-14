package dev.opencode.mobile.network

import dev.opencode.mobile.domain.terminal.TerminalSocketClient
import dev.opencode.mobile.domain.terminal.TerminalSocketListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class OkHttpTerminalSocketClient
    @Inject
    constructor() : TerminalSocketClient {
        private val client =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        private val generation = AtomicInteger(0)
        private var webSocket: WebSocket? = null

        override fun open(
            url: String,
            listener: TerminalSocketListener,
            authorization: String?,
        ) {
            val id = generation.incrementAndGet()
            webSocket?.cancel()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply {
                        if (!authorization.isNullOrEmpty()) {
                            header("Authorization", authorization)
                        }
                    }.build()
            webSocket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            if (id != generation.get()) return
                            listener.onOpen()
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (id != generation.get()) return
                            listener.onText(text)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            if (id != generation.get()) return
                            listener.onBinary(bytes.toByteArray())
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            if (id != generation.get()) return
                            listener.onClosed(code)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (id != generation.get()) return
                            listener.onClosed(ABNORMAL_CLOSE_CODE)
                        }
                    },
                )
        }

        override fun sendText(text: String) {
            webSocket?.send(text)
        }

        override fun close() {
            generation.incrementAndGet()
            webSocket?.close(NORMAL_CLOSE_CODE, null)
            webSocket = null
        }

        companion object {
            private const val NORMAL_CLOSE_CODE = 1000
            private const val ABNORMAL_CLOSE_CODE = 1006
        }
    }
