package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileIdentityTest {
    @Test
    fun credentialFingerprint_isStableHexPrefix() {
        val first = ConnectionProfileIdentity.credentialFingerprint("opencode", "secret")
        val second = ConnectionProfileIdentity.credentialFingerprint("opencode", "secret")
        assertEquals(16, first.length)
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun credentialFingerprint_differsWhenPasswordChanges() {
        assertNotEquals(
            ConnectionProfileIdentity.credentialFingerprint("opencode", "old"),
            ConnectionProfileIdentity.credentialFingerprint("opencode", "new"),
        )
    }

    @Test
    fun hasSameIdentity_falseWhenPasswordChangesOnSameUrl() {
        val original =
            ConnectionProfile(
                baseUrl = "http://127.0.0.1:4096/",
                username = "opencode",
                password = "old",
            )
        val rotated = original.copy(password = "new")
        assertTrue(
            ConnectionProfileIdentity.sameConnectionIdentity(
                original.baseUrl,
                original.username,
                rotated.baseUrl,
                rotated.username,
            ),
        )
        assertFalse(original.hasSameIdentity(rotated))
    }

    @Test
    fun hasSameIdentity_trueWhenBaseUrlMovesWithinServerUrls() {
        val lan = "http://192.168.1.60:4096/"
        val tailscale = "http://100.64.0.15:4096/"
        val original =
            ConnectionProfile(
                id = "profile-1",
                baseUrl = lan,
                username = "opencode",
                password = "token",
                serverUrls = listOf(lan, tailscale),
            )
        val moved = original.copy(baseUrl = tailscale)
        assertTrue(original.hasSameIdentity(moved))
    }

    @Test
    fun hasSameIdentity_falseWhenMovedBaseUrlChangesPassword() {
        val lan = "http://192.168.1.60:4096/"
        val tailscale = "http://100.64.0.15:4096/"
        val original =
            ConnectionProfile(
                id = "profile-1",
                baseUrl = lan,
                username = "opencode",
                password = "token",
                serverUrls = listOf(lan, tailscale),
            )
        val moved = original.copy(baseUrl = tailscale, password = "other")
        assertFalse(original.hasSameIdentity(moved))
    }

    @Test
    fun hasSameIdentity_falseWhenBaseUrlIsOutsideServerUrls() {
        val lan = "http://192.168.1.60:4096/"
        val tailscale = "http://100.64.0.15:4096/"
        val original =
            ConnectionProfile(
                id = "profile-1",
                baseUrl = lan,
                username = "opencode",
                password = "token",
                serverUrls = listOf(lan, tailscale),
            )
        val moved = original.copy(baseUrl = "http://10.0.0.9:4096/")
        assertFalse(original.hasSameIdentity(moved))
    }
}
