package dev.opencode.mobile.domain.t3

/**
 * Foreground liveness hook for the T3 websocket. Mirrors the SSE rule: the
 * socket is kept open only while the app is foregrounded and a T3 profile is
 * active. [stop] tears the socket down and clears runtime caches.
 */
interface T3LiveSession {
    fun start()

    suspend fun stop()
}

object NoopT3LiveSession : T3LiveSession {
    override fun start() = Unit

    override suspend fun stop() = Unit
}
