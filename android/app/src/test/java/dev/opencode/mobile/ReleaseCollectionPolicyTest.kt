package dev.opencode.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCollectionPolicyTest {
    @Test
    fun crashAndAnalyticsCollectionFollowTheConsentToggle() {
        assertTrue(ReleaseCollectionPolicy.collectionEnabled(consented = true))
        assertFalse(ReleaseCollectionPolicy.collectionEnabled(consented = false))
    }
}
