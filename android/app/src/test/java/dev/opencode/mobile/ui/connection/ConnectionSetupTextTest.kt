package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSetupTextTest {
    @Test
    fun screenTitle_followsTheMode() {
        assertEquals(
            UiText.resource(R.string.connection_setup_title_first),
            ConnectionSetupUiState(mode = ConnectionSetupMode.First).screenTitle,
        )
        assertEquals("Add server", ConnectionSetupUiState(mode = ConnectionSetupMode.Add).screenTitle.english())
        assertEquals("Edit server", ConnectionSetupUiState(mode = ConnectionSetupMode.Edit).screenTitle.english())
    }

    @Test
    fun primaryActionLabel_whileConnectingAnEdit_saysSaving() {
        val state = ConnectionSetupUiState(mode = ConnectionSetupMode.Edit, state = ConnectionSetupState.Connecting)

        assertEquals(UiText.resource(R.string.connection_setup_action_saving), state.primaryActionLabel)
        assertEquals("Saving…", state.primaryActionLabel.english())
    }

    @Test
    fun cleartextWarning_oneHost_namesTheHostVerbatim() {
        val text = cleartextWarningMessage(listOf("203.0.113.5:4096"))

        assertEquals(
            UiText.resource(R.string.connection_cleartext_message_one_host, UiText.verbatim("203.0.113.5:4096")),
            text,
        )
        assertEquals(
            "203.0.113.5:4096 uses plain http outside your local network or Tailscale. " +
                "Your server password and everything in your sessions would travel unencrypted. " +
                "Prefer https, a VPN, or a tunnel.",
            text.english(),
        )
    }

    @Test
    fun cleartextWarning_severalHosts_joinsTheHosts() {
        assertEquals(
            "a.example.com, b.example.com use plain http",
            cleartextWarningMessage(listOf("a.example.com", "b.example.com")).english().substringBefore(" outside"),
        )
    }

    @Test
    fun cleartextWarning_noHosts_namesThisAddress() {
        assertEquals(
            "This address uses plain http",
            cleartextWarningMessage(emptyList()).english().substringBefore(" outside"),
        )
    }

    @Test
    fun probeErrors_mapToConnectionErrorText() {
        assertEquals("Authentication failed", ServerProbeError.Http(401, null).userMessageText().english())
        assertEquals("Unable to reach the server", ServerProbeError.Http(500, null).userMessageText().english())
        assertEquals(
            "Unable to reach the server",
            ServerProbeError.Transport(IllegalStateException("boom")).userMessageText().english(),
        )
        assertEquals("Server reported unhealthy", ServerProbeError.Unhealthy(null).userMessageText().english())
    }

    @Test
    fun savedAddressRow_unreachable_keepsTheHostVerbatim() {
        val state =
            ConnectionSetupUiState(
                baseUrl = "http://192.168.1.2:4096/",
                serverUrls = listOf("http://192.168.1.2:4096/"),
                addressProbeFinished = true,
                unreachableUrls = setOf("http://192.168.1.2:4096/"),
            )

        val row = qrAddressRows(state).single()

        assertEquals(
            UiText.resource(R.string.connection_setup_address_not_reachable, UiText.verbatim("192.168.1.2:4096")),
            row.subtitle,
        )
        assertEquals("192.168.1.2:4096 · not reachable", row.subtitle.english())
    }

    @Test
    fun pairAgainMessage_isAResource() {
        assertEquals(
            UiText.resource(R.string.connection_setup_pair_again_unreachable),
            ConnectionSetupUiState(
                serverUrls = listOf("http://192.168.1.2:4096/"),
                addressRecovery = SavedAddressRecovery.Unreachable,
            ).pairAgainMessage,
        )
    }
}
