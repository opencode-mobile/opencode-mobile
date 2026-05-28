package dev.opencode.mobile.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppRootState {
    Loading,
    Onboarding,
    Home,
}

@HiltViewModel
class AppRootViewModel
    @Inject
    constructor(
        private val connectionProfileStore: ConnectionProfileStore,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val httpClient: OpencodeHttpClient,
        private val sseHttpClient: OpencodeSseHttpClient,
        private val foregroundEventStream: ForegroundEventStream,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AppRootState.Loading)
        val state: StateFlow<AppRootState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                loadInitialState()
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileSaved.collect {
                    enterHome()
                }
            }
        }

        private suspend fun loadInitialState() {
            activeConnectionManager.ensureLoaded()
            if (activeConnectionManager.current() != null) {
                httpClient.rebuild()
                sseHttpClient.rebuild()
                enterHome()
            } else {
                foregroundEventStream.stop()
                _state.value = AppRootState.Onboarding
            }
        }

        private fun enterHome() {
            httpClient.rebuild()
            sseHttpClient.rebuild()
            _state.value = AppRootState.Home
            foregroundEventStream.start()
        }
    }
