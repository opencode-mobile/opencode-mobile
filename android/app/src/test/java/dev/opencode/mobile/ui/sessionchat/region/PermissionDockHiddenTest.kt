package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.session.PermissionRequest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDockHiddenTest {
    private fun request(id: String) =
        PermissionRequest(
            id = id,
            sessionId = "ses_1",
            permission = "shell",
            patterns = listOf("git status"),
            metadata = JsonObject(emptyMap()),
            always = emptyList(),
            toolMessageId = null,
            toolCallId = null,
        )

    @Test
    fun hidingKeepsTheRequestPendingUntilANewOneArrives() {
        val hidden = SessionChatInboxRegionState(permissionRequests = listOf(request("per_1")), permissionHiddenId = "per_1")
        assertTrue(hidden.isPermissionDockHidden)
        // Still pending: questions stay queued behind it.
        assertTrue(hidden.visibleQuestionRequest == null)

        val next = hidden.copy(permissionRequests = listOf(request("per_2")))
        assertFalse(next.isPermissionDockHidden)
        assertFalse(SessionChatInboxRegionState(permissionRequests = listOf(request("per_1"))).isPermissionDockHidden)
    }
}
