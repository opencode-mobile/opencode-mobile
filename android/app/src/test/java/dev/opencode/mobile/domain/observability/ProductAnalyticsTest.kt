package dev.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAnalyticsTest {
    @Test
    fun `event names avoid firebase reserved screen_view`() {
        assertEquals("product_screen_view", ProductAnalytics.Event.SCREEN_VIEW)
        assertEquals("product_user_action", ProductAnalytics.Event.USER_ACTION)
    }

    @Test
    fun `screen and action ids use shared product vocabulary`() {
        assertEquals("session_chat", ProductAnalytics.Screen.SESSION_CHAT)
        assertEquals("composer_send", ProductAnalytics.Action.COMPOSER_SEND)
        assertEquals(
            "settings_toggle_product_analytics",
            ProductAnalytics.Action.SETTINGS_TOGGLE_PRODUCT_ANALYTICS,
        )
    }

    @Test
    fun `suppression drops every event until its handle closes`() {
        val sent = mutableListOf<String>()
        val previous = ProductAnalytics.sink
        ProductAnalytics.sink = { name, _ -> sent += name }
        try {
            val suppression = ProductAnalytics.suppress()
            assertTrue(ProductAnalytics.isSuppressed)
            ProductAnalytics.logScreenView(ProductAnalytics.Screen.SESSION_CHAT, ProductAnalytics.Presentation.ROUTE)
            ProductAnalytics.logUserAction(ProductAnalytics.Screen.SESSION_CHAT, ProductAnalytics.Action.COMPOSER_SEND)
            assertTrue(sent.isEmpty())

            suppression.close()
            suppression.close()
            assertFalse(ProductAnalytics.isSuppressed)
            ProductAnalytics.logUserAction(ProductAnalytics.Screen.SESSION_CHAT, ProductAnalytics.Action.COMPOSER_SEND)
            assertEquals(listOf(ProductAnalytics.Event.USER_ACTION), sent)
        } finally {
            ProductAnalytics.sink = previous
        }
    }
}
