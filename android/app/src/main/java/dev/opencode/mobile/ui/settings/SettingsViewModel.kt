package dev.opencode.mobile.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.message.BlockDecorationCache
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.textmate.TextMateLogbook
import dev.opencode.mobile.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private val sessionTodoLiveStore: SessionTodoLiveStore,
        private val sessionReadyStore: SessionReadyStore,
        private val messageRepository: MessageRepository,
        private val messageContentPreparer: MessageContentPreparer,
        private val textMateLogbook: TextMateLogbook,
        private val diagnosticLogBuffer: DiagnosticLogBuffer,
        private val sessionCache: SessionCache,
    ) : ViewModel() {
        private val _sessionCacheSizeLabel = MutableStateFlow("Empty")
        val sessionCacheSizeLabel: StateFlow<String> = _sessionCacheSizeLabel.asStateFlow()
        private val _serversSummary = MutableStateFlow(ServersSummary())
        val serversSummary: StateFlow<ServersSummary> = _serversSummary.asStateFlow()

        fun refreshServersSummary() {
            viewModelScope.launch {
                val profiles = connectionSessionCoordinator.listProfiles()
                val active = connectionSessionCoordinator.currentProfile()
                _serversSummary.value =
                    ServersSummary(
                        count = profiles.size,
                        activeName = active?.displayName,
                    )
            }
        }

        fun refreshSessionCacheSize() {
            viewModelScope.launch {
                val bytes = runCatching { sessionCache.estimatedSizeBytes() }.getOrDefault(0L)
                _sessionCacheSizeLabel.value = SessionCacheSizeFormatter.format(bytes)
            }
        }

        fun clearSessionCache() {
            viewModelScope.launch {
                runCatching { sessionCache.clearAll() }
                refreshSessionCacheSize()
            }
        }

        fun clearAllData() {
            viewModelScope.launch {
                foregroundEventStream.stop()
                connectionSessionCoordinator.clearConnectionData()
                permissionLiveStore.clearCache()
                questionLiveStore.clearCache()
                sessionLiveStore.clearCache()
                sessionStatusLiveStore.clearCache()
                sessionTodoLiveStore.clearCache()
                sessionReadyStore.clearCache()
                messageRepository.clearMessageCache()
                runCatching { sessionCache.clearAll() }
                ThemeManager.clearAll()
                HomeDisplayPreferences.clear()
                MarkdownHighlightPreferences.clear()
                AnalyticsConsentPreferences.clear()
                AppOnboardingPreferences.clear()
                SessionChatDisplayPreferences.clear()
                messageContentPreparer.clearDocumentCache()
                BlockDecorationCache.clear()
                textMateLogbook.clear()
                diagnosticLogBuffer.clear()
                refreshSessionCacheSize()
            }
        }

        fun importThemeFromUri(uri: Uri) {
            ThemeManager.importThemeFromUri(context, uri)
        }

        fun applyPreset(id: String) {
            ThemeManager.applyPreset(id)
        }

        fun setHideUserDirectoryPrefix(enabled: Boolean) {
            HomeDisplayPreferences.updateHideUserDirectoryPrefix(enabled)
        }

        fun setRecentWindowHours(hours: Int) {
            HomeDisplayPreferences.updateRecentWindowHours(hours)
        }

        fun setCollapseConsecutiveToolCalls(enabled: Boolean) {
            SessionChatDisplayPreferences.updateCollapseConsecutiveToolCalls(enabled)
        }

        fun setTextMateEnabled(enabled: Boolean) {
            MarkdownHighlightPreferences.updateTextMateEnabled(enabled)
            messageContentPreparer.clearDocumentCache()
            BlockDecorationCache.clear()
        }

        fun setProductAnalyticsConsented(enabled: Boolean) {
            AnalyticsConsentPreferences.updateProductAnalyticsConsented(enabled)
        }
    }

data class ServersSummary(
    val count: Int = 0,
    val activeName: String? = null,
) {
    val subtitle: String
        get() =
            when {
                count == 0 -> "No servers saved"
                activeName != null -> "$count saved · $activeName active"
                else -> "$count saved"
            }
}
