package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.MessageLiveStore
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.theme.ThemeManager
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val foregroundEventStream: ForegroundEventStream,
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val messageLiveStore: MessageLiveStore,
    ) : ViewModel() {
        fun clearAllData() {
            viewModelScope.launch {
                foregroundEventStream.stop()
                connectionSessionCoordinator.clearConnectionData()
                sessionLiveStore.clearCache()
                sessionStatusLiveStore.clearCache()
                messageLiveStore.clearCache()
                ThemeManager.clearAll()
            }
        }
    }
