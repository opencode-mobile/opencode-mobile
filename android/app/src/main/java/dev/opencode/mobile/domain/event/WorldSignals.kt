package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class WorldSignal {
    Foreground,
    Background,
    BackgroundStop,
    PathReady,
    PathLost,
    ProfileSwitched,
}

interface WorldSignals {
    val signals: SharedFlow<WorldSignal>

    fun register() {}
}

object SilentWorldSignals : WorldSignals {
    override val signals: SharedFlow<WorldSignal> = MutableSharedFlow()
}

class FakeWorldSignals : WorldSignals {
    private val _signals = MutableSharedFlow<WorldSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<WorldSignal> = _signals.asSharedFlow()

    fun emit(signal: WorldSignal) {
        _signals.tryEmit(signal)
    }
}

fun WorldSignal.marksStale(): Boolean =
    this == WorldSignal.Background || this == WorldSignal.PathLost

fun WorldSignal.catchUpReason(): String? =
    when (this) {
        WorldSignal.Foreground -> StoreRefreshReason.BACKGROUND_RETURN
        WorldSignal.PathReady -> StoreRefreshReason.PATH_READY
        else -> null
    }

object StoreRefreshReason {
    const val BACKGROUND_RETURN = "background-return"
    const val PATH_READY = "path-ready"
    const val PULL_TO_REFRESH = "pull-to-refresh"
    const val INITIAL_SUBSCRIBE = "initial-subscribe"
    const val STREAM_RECONNECTED = "stream.reconnected"
    const val SERVER_CONNECTED = "server.connected"
}
