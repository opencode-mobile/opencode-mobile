package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.OpenCodeReleaseObservability
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.message.BlockDecorationCache
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SplitPanePreferences
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.textmate.TextMateLogbook
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val activeConnectionManager: ActiveConnectionManager,
        private val projectionCaches: ProjectionCaches,
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
                        letter = letterCharacterForProject(active?.displayName ?: "Servers"),
                        letterAccent = letterAccentForWorktree(active?.id ?: active?.displayName ?: "servers"),
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
                connectionSessionCoordinator.clearConnectionData()
                projectionCaches.clearAll()
                runCatching { sessionCache.clearAll() }
                ThemeManager.clearAll()
                HomeDisplayPreferences.clear()
                MarkdownHighlightPreferences.clear()
                AnalyticsConsentPreferences.clear()
                AppOnboardingPreferences.clear()
                DeveloperToolsPreferences.clear()
                SessionChatDisplayPreferences.clear()
                SplitPanePreferences.clear()
                FeatureFlags.clearOverrides()
                messageContentPreparer.clearDocumentCache()
                BlockDecorationCache.clear()
                textMateLogbook.clear()
                diagnosticLogBuffer.clear()
                refreshSessionCacheSize()
            }
        }

        fun applyPreset(id: String) {
            ThemeManager.applyPreset(id)
        }

        fun applySyntaxPreset(id: String) {
            ThemeManager.applySyntaxPreset(id)
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

        fun setChipTimeline(enabled: Boolean) {
            SessionChatDisplayPreferences.updateChipTimeline(enabled)
        }

        fun setFollowUpDelivery(value: FollowUpDelivery) {
            SessionChatDisplayPreferences.updateFollowUpDelivery(value)
        }

        fun supportsFollowUpDelivery(): Boolean = activeConnectionManager.currentCapabilities().supportsFollowUpDelivery

        fun setTextMateEnabled(enabled: Boolean) {
            MarkdownHighlightPreferences.updateTextMateEnabled(enabled)
            messageContentPreparer.clearDocumentCache()
            BlockDecorationCache.clear()
        }

        /** Through the observability seam so Crashlytics and automatic Analytics follow at once. */
        fun setProductAnalyticsConsented(enabled: Boolean) {
            OpenCodeReleaseObservability.setProductAnalyticsConsented(enabled)
        }
    }

data class ServersSummary(
    val count: Int = 0,
    val activeName: String? = null,
    val letter: Char = 'S',
    val letterAccent: LetterAccent = LetterAccent.Blue,
) {
    val subtitle: String
        get() =
            when {
                count == 0 -> "No servers saved"
                activeName != null -> "$count saved · $activeName active"
                else -> "$count saved"
            }
}
