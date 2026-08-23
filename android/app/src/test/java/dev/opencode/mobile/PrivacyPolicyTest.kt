package dev.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyPolicyTest {
    @Test
    fun `url is the hosted docs policy`() {
        assertEquals(
            "https://opencode-mobile.github.io/opencode-mobile-docs/privacy/",
            PrivacyPolicy.URL,
        )
    }
}
