package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.OpenCodeReleaseObservability
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.message.BlockDecorationCache
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppLockPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.HomeSessionRowStyle
import dev.opencode.mobile.domain.preferences.PerformanceOverlayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.releasenotes.WhatsNew
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.SplitPanePreferences
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
        private val diagnosticLogBuffer: DiagnosticLogBuffer,
        private val sessionCache: SessionCache,
        whatsNew: WhatsNew,
    ) : ViewModel() {
        /** Settings → What's new carries the New tag while true. */
        val whatsNewBadgePending: StateFlow<Boolean> = whatsNew.badgePending

        private val _sessionCacheSizeLabel = MutableStateFlow(SessionCacheSizeFormatter.format(0L))
        val sessionCacheSizeLabel: StateFlow<UiText> = _sessionCacheSizeLabel.asStateFlow()
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
                AnalyticsConsentPreferences.clear()
                // Apply the reset default to Crashlytics and Analytics collection now, not at next launch.
                OpenCodeReleaseObservability.setProductAnalyticsConsented(
                    AnalyticsConsentPreferences.productAnalyticsConsented,
                )
                AppLockPreferences.clear()
                AppOnboardingPreferences.clear()
                PerformanceOverlayPreferences.clear()
                SessionChatDisplayPreferences.clear()
                // A wipe is a fresh install: settle the timeline now so the update-only picker never shows.
                SessionChatDisplayPreferences.resolveTimelineForInstall(existingInstall = false)
                SplitPanePreferences.clear()
                FeatureFlags.clearOverrides()
                messageContentPreparer.clearDocumentCache()
                BlockDecorationCache.clear()
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

        fun setShowsWorktrees(enabled: Boolean) {
            HomeDisplayPreferences.updateShowsWorktrees(enabled)
        }

        fun setSessionRowStyle(style: HomeSessionRowStyle) {
            HomeDisplayPreferences.updateSessionRowStyle(style)
        }

        fun setRecentWindowHours(hours: Int) {
            HomeDisplayPreferences.updateRecentWindowHours(hours)
        }

        fun setCollapseConsecutiveToolCalls(enabled: Boolean) {
            SessionChatDisplayPreferences.updateCollapseConsecutiveToolCalls(enabled)
        }

        fun setTimelineStyle(style: SessionChatTimelineStyle) {
            SessionChatDisplayPreferences.updateTimelineStyle(style)
        }

        fun setFollowUpDelivery(value: FollowUpDelivery) {
            SessionChatDisplayPreferences.updateFollowUpDelivery(value)
        }

        fun supportsFollowUpDelivery(): Boolean = activeConnectionManager.currentCapabilities().supportsFollowUpDelivery

        /** Through the observability seam so Crashlytics and automatic Analytics follow at once. */
        fun setProductAnalyticsConsented(enabled: Boolean) {
            OpenCodeReleaseObservability.setProductAnalyticsConsented(enabled)
        }

        fun setAppLockEnabled(enabled: Boolean) {
            AppLockPreferences.updateEnabled(enabled)
        }
    }

data class ServersSummary(
    val count: Int = 0,
    val activeName: String? = null,
    val letter: Char = 'S',
    val letterAccent: LetterAccent = LetterAccent.Blue,
) {
    /** The active profile name is user content and stays verbatim. */
    val subtitle: UiText
        get() =
            when {
                count == 0 -> UiText.resource(R.string.settings_servers_summary_none)
                activeName != null ->
                    UiText.resource(R.string.settings_servers_summary_saved_active, count, UiText.verbatim(activeName))
                else -> UiText.plural(R.plurals.settings_servers_summary_saved, count)
            }
}
