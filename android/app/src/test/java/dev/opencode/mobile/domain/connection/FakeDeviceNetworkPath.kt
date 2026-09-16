package dev.opencode.mobile.domain.connection

import kotlinx.coroutines.flow.MutableStateFlow

class FakeDeviceNetworkPath(
    initial: DeviceNetworkPathStatus = DeviceNetworkPathStatus.Ready,
) : DeviceNetworkPath {
    override val status = MutableStateFlow(initial)

    fun set(value: DeviceNetworkPathStatus) {
        status.value = value
    }
}
