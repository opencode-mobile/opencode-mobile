package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DefaultLiveUpdates(
    worldSignals: WorldSignals,
    stream: ForegroundEventStream,
    path: DeviceNetworkPath,
    hasActiveConnection: () -> Boolean,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : LiveUpdates {
    private val connectionPresent = MutableStateFlow(hasActiveConnection())

    init {
        scope.launch {
            worldSignals.signals.collect { signal ->
                if (signal == WorldSignal.ProfileSwitched || signal == WorldSignal.Foreground) {
                    connectionPresent.value = hasActiveConnection()
                }
            }
        }
    }

    override val chrome: StateFlow<LiveUpdatesChrome> =
        combine(
            worldSignals.occupancy,
            stream.connectionState,
            path.status,
            connectionPresent,
        ) { occupancy, connectionState, pathStatus, hasConnection ->
            composeLiveUpdates(
                connectionState = connectionState,
                path = pathStatus,
                occupancy = occupancy,
                hasConnection = hasConnection,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue =
                composeLiveUpdates(
                    connectionState = stream.connectionState.value,
                    path = path.status.value,
                    occupancy = worldSignals.occupancy.value,
                    hasConnection = connectionPresent.value,
                ),
        )
}
