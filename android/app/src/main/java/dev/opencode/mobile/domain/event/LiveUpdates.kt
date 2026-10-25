package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveUpdatesLabel(
    val label: String,
) {
    Live("live"),
    Connecting("connecting"),
    Offline("offline"),
    AuthFailed("Authentication failed"),
}

data class LiveUpdatesChrome(
    val label: LiveUpdatesLabel,
    val canMutate: Boolean,
    val composerEnabled: Boolean = canMutate,
)

interface LiveUpdates {
    val chrome: StateFlow<LiveUpdatesChrome>
}

class FixedLiveUpdates(
    chrome: LiveUpdatesChrome =
        LiveUpdatesChrome(
            label = LiveUpdatesLabel.Live,
            canMutate = true,
            composerEnabled = true,
        ),
) : LiveUpdates {
    private val _chrome = MutableStateFlow(chrome)
    override val chrome: StateFlow<LiveUpdatesChrome> = _chrome.asStateFlow()

    fun set(chrome: LiveUpdatesChrome) {
        _chrome.value = chrome
    }
}

fun composeLiveUpdates(
    connectionState: EventStreamConnectionState,
    path: DeviceNetworkPathStatus,
    occupancy: StreamOccupancy,
    hasConnection: Boolean,
): LiveUpdatesChrome {
    val label =
        when {
            connectionState == EventStreamConnectionState.AuthFailed -> LiveUpdatesLabel.AuthFailed
            !hasConnection -> LiveUpdatesLabel.Offline
            path == DeviceNetworkPathStatus.Unavailable -> LiveUpdatesLabel.Offline
            occupancy == StreamOccupancy.Stopped -> LiveUpdatesLabel.Offline
            // No protocol answered the last check: the server is down or replaced.
            connectionState == EventStreamConnectionState.Disconnected -> LiveUpdatesLabel.Offline
            connectionState == EventStreamConnectionState.Connected -> LiveUpdatesLabel.Live
            else -> LiveUpdatesLabel.Connecting
        }
    val composerEnabled =
        hasConnection &&
            path == DeviceNetworkPathStatus.Ready &&
            occupancy != StreamOccupancy.Stopped &&
            connectionState != EventStreamConnectionState.AuthFailed
    val canMutate = label == LiveUpdatesLabel.Live
    return LiveUpdatesChrome(label = label, canMutate = canMutate, composerEnabled = composerEnabled)
}
