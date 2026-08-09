package dev.opencode.mobile

import android.app.Application
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.textmate.TextMateProcessPressureObserver
import dev.opencode.mobile.markdown.OpenCodeAndroidMarkdownAssets
import dev.opencode.mobile.theme.ThemeManager

@HiltAndroidApp
class OpenCodeMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.initialize(this)
        HomeDisplayPreferences.initialize(this)
        MarkdownHighlightPreferences.initialize(this)
        AnalyticsConsentPreferences.initialize(this)
        AppOnboardingPreferences.initialize(this)
        OpenCodeAndroidMarkdownAssets.init(this)
        OpenCodeReleaseObservability.initialize(this)
        EntryPointAccessors
            .fromApplication(this, TextMatePressureEntryPoint::class.java)
            .textMateProcessPressureObserver()
            .start()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TextMatePressureEntryPoint {
    fun textMateProcessPressureObserver(): TextMateProcessPressureObserver
}
