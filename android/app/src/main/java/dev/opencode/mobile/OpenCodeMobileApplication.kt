package dev.opencode.mobile

import android.app.Application
import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.MainThreadHangWatchdog
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SplitPanePreferences
import dev.opencode.mobile.domain.textmate.TextMateProcessPressureObserver
import dev.opencode.mobile.markdown.OpenCodeAndroidMarkdownAssets
import dev.opencode.mobile.platform.FrameworkSdkLevel
import dev.opencode.mobile.theme.ThemeManager

@HiltAndroidApp
class OpenCodeMobileApplication : Application() {
    override fun attachBaseContext(base: Context) {
        FrameworkSdkLevel.alignToFramework()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        ThemeManager.initialize(this)
        HomeDisplayPreferences.initialize(this)
        MarkdownHighlightPreferences.initialize(this)
        AnalyticsConsentPreferences.initialize(this)
        AppOnboardingPreferences.initialize(this)
        DeveloperToolsPreferences.initialize(this)
        SessionChatDisplayPreferences.initialize(this)
        SplitPanePreferences.initialize(this)
        FeatureFlags.initialize(this)
        OpenCodeAndroidMarkdownAssets.init(this)
        OpenCodeReleaseObservability.initialize(this)
        EntryPointAccessors
            .fromApplication(this, TextMatePressureEntryPoint::class.java)
            .textMateProcessPressureObserver()
            .start()
        val hangWatchdogEntry =
            EntryPointAccessors.fromApplication(this, HangWatchdogEntryPoint::class.java)
        hangWatchdogEntry.diagnosticLogBuffer()
        hangWatchdogEntry.mainThreadHangWatchdog().start()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TextMatePressureEntryPoint {
    fun textMateProcessPressureObserver(): TextMateProcessPressureObserver
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HangWatchdogEntryPoint {
    fun diagnosticLogBuffer(): DiagnosticLogBuffer

    fun mainThreadHangWatchdog(): MainThreadHangWatchdog
}
