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
)

interface LiveUpdates {
    val chrome: StateFlow<LiveUpdatesChrome>
}

class FixedLiveUpdates(
    chrome: LiveUpdatesChrome =
        LiveUpdatesChrome(
            label = LiveUpdatesLabel.Live,
            canMutate = true,
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
    val canMutate =
        hasConnection &&
            path == DeviceNetworkPathStatus.Ready &&
            occupancy != StreamOccupancy.Stopped
    val label =
        when {
            connectionState == EventStreamConnectionState.AuthFailed -> LiveUpdatesLabel.AuthFailed
            !hasConnection -> LiveUpdatesLabel.Offline
            path == DeviceNetworkPathStatus.Unavailable -> LiveUpdatesLabel.Offline
            occupancy == StreamOccupancy.Stopped -> LiveUpdatesLabel.Offline
            connectionState == EventStreamConnectionState.Connected -> LiveUpdatesLabel.Live
            else -> LiveUpdatesLabel.Connecting
        }
    val effectiveCanMutate =
        if (connectionState == EventStreamConnectionState.AuthFailed) {
            false
        } else {
            canMutate
        }
    return LiveUpdatesChrome(label = label, canMutate = effectiveCanMutate)
}
