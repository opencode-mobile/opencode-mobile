package dev.opencode.hexcode.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Welcome and Settings › App › Privacy policy open Hex Code's own policy, not OpenCode Mobile's. */
class HexPrivacyPolicyTest {
    @Test
    fun linksHexCodesPolicyOverHttps() {
        assertEquals("https://opencode-mobile.github.io/opencode-mobile-docs/hexcode/privacy/", HexPrivacyPolicy.URL)
    }
}
