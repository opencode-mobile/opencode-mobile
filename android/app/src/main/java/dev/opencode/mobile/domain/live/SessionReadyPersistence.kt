package dev.opencode.mobile.domain.live

data class SessionReadyPersistedEntry(
    val sessionId: String,
    val directory: String,
    val atEpochMs: Long,
    val profileId: String = "",
)

data class SessionReadyPersistedState(
    val ready: List<SessionReadyPersistedEntry> = emptyList(),
    val watched: List<SessionReadyPersistedEntry> = emptyList(),
)

fun interface SessionReadyClock {
    fun nowEpochMs(): Long
}

interface SessionReadyPersistence {
    fun load(): SessionReadyPersistedState

    fun save(state: SessionReadyPersistedState)
}

class InMemorySessionReadyPersistence : SessionReadyPersistence {
    private var state = SessionReadyPersistedState()

    override fun load(): SessionReadyPersistedState = state

    override fun save(state: SessionReadyPersistedState) {
        this.state =
            SessionReadyPersistedState(
                ready = state.ready.toList(),
                watched = state.watched.toList(),
            )
    }
}
