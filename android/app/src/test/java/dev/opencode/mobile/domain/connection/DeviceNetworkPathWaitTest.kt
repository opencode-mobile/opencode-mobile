package dev.opencode.mobile.domain.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNetworkPathWaitTest {
    @Test
    fun waitUntilReady_doesNotOverwriteConcurrentReady() =
        runTest {
            val status = MutableStateFlow(DeviceNetworkPathStatus.Ready)
            withTimeout(1_000) {
                waitUntilDeviceNetworkReady(
                    isCurrentlyReachable = false,
                    status = status,
                )
            }
            assertEquals(DeviceNetworkPathStatus.Ready, status.value)
        }

    @Test
    fun waitUntilReady_promotesReachableUnavailableToReady() =
        runTest {
            val status = MutableStateFlow(DeviceNetworkPathStatus.Unavailable)
            waitUntilDeviceNetworkReady(
                isCurrentlyReachable = true,
                status = status,
            )
            assertEquals(DeviceNetworkPathStatus.Ready, status.value)
        }
}
