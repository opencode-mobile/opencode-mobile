package dev.opencode.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.opencode.mobile.markdown.OpenCodeAndroidMarkdownAssets

@HiltAndroidApp
class OpenCodeMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OpenCodeAndroidMarkdownAssets.init(this)
    }
}

