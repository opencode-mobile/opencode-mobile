package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class ServersUiStateTest {
    private val work = ConnectionProfile(baseUrl = "http://192.168.1.2:4096", username = "opencode", id = "a", name = "Work")
    private val home = ConnectionProfile(baseUrl = "http://10.0.0.1:4096", username = "opencode", id = "b", name = "Home")

    @Test
    fun profileCountLabel_isAPluralOfTheProfileCount() {
        assertEquals(UiText.plural(R.plurals.servers_profile_count, 0), ServersUiState().profileCountLabel)
        assertEquals(
            UiText.plural(R.plurals.servers_profile_count, 1),
            ServersUiState(profiles = listOf(work)).profileCountLabel,
        )
        assertEquals(
            UiText.plural(R.plurals.servers_profile_count, 2),
            ServersUiState(profiles = listOf(work, home)).profileCountLabel,
        )
    }

    @Test
    fun removeDialogTitle_withoutPendingProfile_isTheGenericTitle() {
        assertEquals(UiText.resource(R.string.servers_remove_dialog_title), ServersUiState().removeDialogTitle)
    }

    @Test
    fun removeDialogTitle_namesThePendingProfileVerbatim() {
        val state = ServersUiState(profiles = listOf(work), pendingRemoval = work)

        assertEquals(
            UiText.resource(R.string.servers_remove_dialog_title_named, UiText.verbatim("Work")),
            state.removeDialogTitle,
        )
    }
}
