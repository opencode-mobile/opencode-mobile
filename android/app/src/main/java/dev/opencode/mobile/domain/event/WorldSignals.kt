package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

    fun onConnectionAvailable() {}

    fun emit(signal: WorldSignal) {}
}

object SilentWorldSignals : WorldSignals {
    override val signals: SharedFlow<WorldSignal> = MutableSharedFlow()
    override val occupancy: StateFlow<StreamOccupancy> =
        MutableStateFlow(StreamOccupancy.Active)
}

fun WorldSignal.marksStale(): Boolean =
    this == WorldSignal.BackgroundStop ||
        this == WorldSignal.PathLost ||
        this == WorldSignal.ProfileSwitched

object StoreRefreshReason {
    const val PULL_TO_REFRESH = "pull-to-refresh"
}
