package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

open class FakeWorldSignals : WorldSignals {
    private val _signals = MutableSharedFlow<WorldSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<WorldSignal> = _signals.asSharedFlow()
    private val _occupancy = MutableStateFlow(StreamOccupancy.Active)
    override val occupancy: StateFlow<StreamOccupancy> = _occupancy.asStateFlow()

    override fun emit(signal: WorldSignal) {
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
