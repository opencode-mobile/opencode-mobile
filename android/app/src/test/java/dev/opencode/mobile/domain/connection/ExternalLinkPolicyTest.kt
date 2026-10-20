package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinkPolicyTest {
    @Test
    fun webLinks_open() {
        listOf("https://opencode.ai/docs", "http://192.168.0.1:4096/status", "HTTPS://Example.com")
            .forEach { assertTrue(it, ExternalLinkPolicy.isOpenable(it)) }
    }

    @Test
    fun otherSchemes_areDropped() {
        listOf(
            "tel:+15555550100",
            "file:///etc/passwd",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "opencode-mobile://connect",
            "",
            "   ",
            "not a url",
            null,
        ).forEach { assertFalse(it.toString(), ExternalLinkPolicy.isOpenable(it)) }
    }
}
