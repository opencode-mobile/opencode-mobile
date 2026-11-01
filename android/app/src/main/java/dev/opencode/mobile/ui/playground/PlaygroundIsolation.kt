package dev.opencode.mobile.ui.playground

import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences

/**
 * What keeps the Session Chat Playground from reaching the real app while it is open: no
 * product analytics, and Session Chat display preferences kept in memory and restored after.
 */
object PlaygroundIsolation {
    fun begin(): AutoCloseable {
        val analytics = ProductAnalytics.suppress()
        val preferences = SessionChatDisplayPreferences.sandbox()
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                preferences.close()
                analytics.close()
            }
        }
    }
}
