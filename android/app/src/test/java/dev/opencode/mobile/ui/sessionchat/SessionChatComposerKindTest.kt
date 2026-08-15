package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionChatComposerKindTest {
    @Test
    fun resolve_rootWithoutRequests_isPrompt() {
        assertEquals(
            SessionChatComposerKind.Prompt,
            SessionChatComposerKind.resolve(
                isChildSession = false,
                hasPermission = false,
                hasQuestion = false,
            ),
        )
    }

    @Test
    fun resolve_childWithoutRequests_isChildLockout() {
        assertEquals(
            SessionChatComposerKind.ChildLockout,
            SessionChatComposerKind.resolve(
                isChildSession = true,
                hasPermission = false,
                hasQuestion = false,
            ),
        )
    }

    @Test
    fun resolve_permissionBeatsChildLockoutAndQuestion() {
        assertEquals(
            SessionChatComposerKind.Permission,
            SessionChatComposerKind.resolve(
                isChildSession = true,
                hasPermission = true,
                hasQuestion = true,
            ),
        )
    }

    @Test
    fun resolve_questionBeatsChildLockout() {
        assertEquals(
            SessionChatComposerKind.Question,
            SessionChatComposerKind.resolve(
                isChildSession = true,
                hasPermission = false,
                hasQuestion = true,
            ),
        )
    }
}
