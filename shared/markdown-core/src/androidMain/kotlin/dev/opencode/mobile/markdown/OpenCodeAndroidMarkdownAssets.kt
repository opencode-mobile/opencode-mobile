package dev.opencode.mobile.markdown

import android.content.Context

object OpenCodeAndroidMarkdownAssets {
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    internal fun requireApplicationContext(): Context =
        checkNotNull(applicationContext) {
            "Call OpenCodeAndroidMarkdownAssets.init from Application.onCreate before using the bundled Joni highlighter."
        }
}
