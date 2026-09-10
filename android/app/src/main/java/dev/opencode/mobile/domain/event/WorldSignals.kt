package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WorldSignal {
    Foreground,
    Background,
    BackgroundStop,
    PathReady,
    PathLost,
    ProfileSwitched,
}

enum class StreamOccupancy {
    Active,
    Grace,
    Stopped,
}

interface WorldSignals {
    val signals: SharedFlow<WorldSignal>
    val occupancy: StateFlow<StreamOccupancy>

    fun register() {}
}

object SilentWorldSignals : WorldSignals {
    override val signals: SharedFlow<WorldSignal> = MutableSharedFlow()
    override val occupancy: StateFlow<StreamOccupancy> =
        MutableStateFlow(StreamOccupancy.Active)
}

class FakeWorldSignals : WorldSignals {
    private val _signals = MutableSharedFlow<WorldSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<WorldSignal> = _signals.asSharedFlow()
    private val _occupancy = MutableStateFlow(StreamOccupancy.Active)
    override val occupancy: StateFlow<StreamOccupancy> = _occupancy.asStateFlow()

    fun emit(signal: WorldSignal) {
        when (signal) {
            WorldSignal.Foreground -> _occupancy.value = StreamOccupancy.Active
            WorldSignal.Background -> _occupancy.value = StreamOccupancy.Grace
            WorldSignal.BackgroundStop -> _occupancy.value = StreamOccupancy.Stopped
            WorldSignal.PathReady,
            WorldSignal.PathLost,
            WorldSignal.ProfileSwitched,
            -> Unit
        }
        _signals.tryEmit(signal)
    }
}

fun WorldSignal.marksStale(): Boolean =
    this == WorldSignal.BackgroundStop ||
        this == WorldSignal.PathLost

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
