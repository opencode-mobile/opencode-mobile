package dev.opencode.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAnalyticsConsentPolicyTest {
    @Test
    fun `product events require consent`() {
        assertFalse(ProductAnalyticsConsentPolicy.shouldLog(consented = false))
        assertTrue(ProductAnalyticsConsentPolicy.shouldLog(consented = true))
    }
}
