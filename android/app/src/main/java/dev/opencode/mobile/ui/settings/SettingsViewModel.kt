package dev.opencode.mobile.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.theme.ThemeManager
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val foregroundEventStream: ForegroundEventStream,
        private val permissionLiveStore: PermissionLiveStore,
        private val questionLiveStore: QuestionLiveStore,
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val messageRepository: MessageRepository,
    ) : ViewModel() {
        fun clearAllData() {
            viewModelScope.launch {
                foregroundEventStream.stop()
                connectionSessionCoordinator.clearConnectionData()
                permissionLiveStore.clearCache()
                questionLiveStore.clearCache()
                sessionLiveStore.clearCache()
                sessionStatusLiveStore.clearCache()
                messageRepository.clearMessageCache()
                ThemeManager.clearAll()
                HomeDisplayPreferences.clear()
            }
        }

        fun importThemeFromUri(uri: Uri) {
            ThemeManager.importThemeFromUri(context, uri)
        }

        fun applyPreset(id: String) {
            ThemeManager.applyPreset(id)
        }

        fun setShowSessionListOnHome(enabled: Boolean) {
            HomeDisplayPreferences.updateShowSessionListOnHome(enabled)
        }
    }
