package dev.opencode.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.opencode.mobile.markdown.OpenCodeAndroidMarkdownAssets
import dev.opencode.mobile.theme.ThemeManager

@HiltAndroidApp
class OpenCodeMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.initialize(this)
        OpenCodeAndroidMarkdownAssets.init(this)
    }
}

