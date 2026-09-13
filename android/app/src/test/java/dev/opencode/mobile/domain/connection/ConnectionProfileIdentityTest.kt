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
}
