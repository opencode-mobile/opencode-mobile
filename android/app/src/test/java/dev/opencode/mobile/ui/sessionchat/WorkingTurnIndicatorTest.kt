package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkingTurnIndicatorTest {
    @Test
    fun labelForActiveTurn_nullWhenIdle() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = null,
                messages = listOf(user("u1")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsWorkingAfterUserWithNoAssistant() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages = listOf(user("u1")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenAssistantHasText() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages = listOf(user("u1"), assistant("a1", "Hello")),
            ),
        )
    }

    private fun user(id: String) =
        SessionChatMessageItem(
            id = id,
            role = MessageRole.User,
            text = "Hi",
            metadataLabel = "",
        )

    private fun assistant(
        id: String,
        text: String,
    ) = SessionChatMessageItem(
        id = id,
        role = MessageRole.Assistant,
        text = text,
        metadataLabel = "",
    )
}
