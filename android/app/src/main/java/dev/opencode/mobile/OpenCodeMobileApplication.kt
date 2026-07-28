package dev.opencode.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.markdown.OpenCodeAndroidMarkdownAssets
import dev.opencode.mobile.theme.ThemeManager

@HiltAndroidApp
class OpenCodeMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.initialize(this)
        HomeDisplayPreferences.initialize(this)
        MarkdownHighlightPreferences.initialize(this)
        OpenCodeAndroidMarkdownAssets.init(this)
        OpenCodeReleaseObservability.initialize(this)
    }
}
