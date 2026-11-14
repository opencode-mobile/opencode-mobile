package dev.opencode.mobile.domain.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

enum class DeviceNetworkPathStatus {
    Ready,
    Unavailable,
}

interface DeviceNetworkPath {
    val status: StateFlow<DeviceNetworkPathStatus>

    val updates: Flow<DeviceNetworkPathStatus>
        get() = status

    suspend fun waitUntilReady() {
        status.first { it == DeviceNetworkPathStatus.Ready }
    }
}

/**
 * Wait for a ready path without clobbering a Ready that arrived after a
 * stale "not reachable" read. Writing Unavailable in that window lost the
 * callback and hung occupancy before leases opened.
 */
internal suspend fun waitUntilDeviceNetworkReady(
    isCurrentlyReachable: Boolean,
    status: MutableStateFlow<DeviceNetworkPathStatus>,
) {
    if (status.value == DeviceNetworkPathStatus.Ready) return
    if (isCurrentlyReachable) {
        status.value = DeviceNetworkPathStatus.Ready
        return
    }
    status.first { it == DeviceNetworkPathStatus.Ready }
}

object ReadyDeviceNetworkPath : DeviceNetworkPath {
    override val status: StateFlow<DeviceNetworkPathStatus> =
        MutableStateFlow(DeviceNetworkPathStatus.Ready)
}
