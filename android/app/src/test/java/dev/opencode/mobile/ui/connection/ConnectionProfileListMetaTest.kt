package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionProfileListMetaTest {
    @Test
    fun activeWithProtocol_captionAndActivePill() {
        val profile = profile(ServerProtocol.V2)
        assertEquals("API v2", profile.listCaption(isActive = true))
        val status = profile.listStatus(isActive = true)
        assertEquals("Active", status?.text)
        assertEquals(OpenCodeListItemStatusTone.Interactive, status?.tone)
    }

    @Test
    fun otherWithProtocol_compactInfoPill() {
        val profile = profile(ServerProtocol.V1)
        assertNull(profile.listCaption(isActive = false))
        val status = profile.listStatus(isActive = false)
        assertEquals("v1", status?.text)
        assertEquals(OpenCodeListItemStatusTone.Info, status?.tone)
    }

    @Test
    fun unknownProtocol_omitsApiIndicator() {
        val profile = profile(null)
        assertNull(profile.listCaption(isActive = true))
        assertNull(profile.listCaption(isActive = false))
        assertEquals("Active", profile.listStatus(isActive = true)?.text)
        assertNull(profile.listStatus(isActive = false))
    }

    private fun profile(protocol: ServerProtocol?) =
        ConnectionProfile(
            baseUrl = "http://192.168.1.42:4096/",
            username = "opencode",
            serverProtocol = protocol,
            id = "mac-mini",
            name = "Mac Mini",
        )
}
