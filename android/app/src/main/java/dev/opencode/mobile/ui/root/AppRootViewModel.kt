package dev.opencode.mobile.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
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
    AppOnboarding,
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

        private val _presentConnectionSetup = MutableStateFlow(false)
        val presentConnectionSetup: StateFlow<Boolean> = _presentConnectionSetup.asStateFlow()

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
            httpClient.rebuild()
            sseHttpClient.rebuild()
            val hasProfile = connectionProfileStore.activeProfile() != null
            if (!AppOnboardingPreferences.isCompleted && !hasProfile) {
                _state.value = AppRootState.AppOnboarding
            } else {
                enterHome()
            }
        }

        fun completeAppOnboarding(openConnectionSetup: Boolean) {
            AppOnboardingPreferences.markCompleted()
            enterHome()
            if (openConnectionSetup) {
                _presentConnectionSetup.value = true
            }
        }

        fun consumePresentConnectionSetup() {
            _presentConnectionSetup.value = false
        }

        private fun enterHome() {
            httpClient.rebuild()
            sseHttpClient.rebuild()
            _state.value = AppRootState.Home
            foregroundEventStream.stop()
            foregroundEventStream.start()
        }
    }
