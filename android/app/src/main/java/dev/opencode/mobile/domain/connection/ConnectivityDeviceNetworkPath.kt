package dev.opencode.mobile.domain.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityDeviceNetworkPath
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : DeviceNetworkPath {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val _status =
            MutableStateFlow(
                if (connectivityManager.activeNetwork != null) {
                    DeviceNetworkPathStatus.Ready
                } else {
                    DeviceNetworkPathStatus.Unavailable
                },
            )
        override val status: StateFlow<DeviceNetworkPathStatus> = _status.asStateFlow()

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _status.value = DeviceNetworkPathStatus.Ready
                }

                override fun onLost(network: Network) {
                    _status.value =
                        if (connectivityManager.activeNetwork != null) {
                            DeviceNetworkPathStatus.Ready
                        } else {
                            DeviceNetworkPathStatus.Unavailable
                        }
                }

                override fun onUnavailable() {
                    _status.value = DeviceNetworkPathStatus.Unavailable
                }
            }

        init {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }

        override suspend fun waitUntilReady() {
            if (connectivityManager.activeNetwork != null) {
                _status.value = DeviceNetworkPathStatus.Ready
                return
            }
            _status.value = DeviceNetworkPathStatus.Unavailable
            status.first { it == DeviceNetworkPathStatus.Ready }
        }
    }
