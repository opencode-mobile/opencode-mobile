package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.message.FailureAction
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFailureDetailTargetTest {
    @Test
    fun compactWithoutABodyStillOpensASheet() {
        val target =
            sessionFailureDetailTarget(
                text = "Context overflow",
                providerBody = null,
                action = FailureAction.Compact,
                actionLabel = "Compact",
            )
        assertEquals("Context overflow", target?.title)
        assertEquals("", target?.body)
        assertEquals(false, target?.monospace)
        assertEquals(SessionFailureSheetAction.Compact, target?.sheetAction)
        assertEquals("Compact", target?.sheetActionLabel)
    }

    @Test
    fun copyWithABodyUsesTheBodyCopyOnly() {
        val target =
            sessionFailureDetailTarget(
                text = "Something went wrong",
                providerBody = "{\"error\":1}",
                action = FailureAction.Copy,
                actionLabel = "Copy",
            )
        assertEquals("Provider response", target?.title)
        assertEquals(true, target?.monospace)
        assertNull(target?.sheetAction)
    }

    @Test
    fun copyWithoutABodyCopiesTheCaption() {
        val target =
            sessionFailureDetailTarget(
                text = "Something went wrong",
                providerBody = "  ",
                action = FailureAction.Copy,
                actionLabel = "Copy",
            )
        assertEquals(SessionFailureSheetAction.CopyCaption, target?.sheetAction)
        assertEquals("Something went wrong", target?.title)
        assertEquals(false, target?.monospace)
    }

    @Test
    fun authWithABodyHasNoAction() {
        val target =
            sessionFailureDetailTarget(
                text = "HTTP 401 · Sign in",
                providerBody = "denied",
                action = null,
                actionLabel = null,
            )
        assertEquals("Provider response", target?.title)
        assertEquals("denied", target?.body)
        assertNull(target?.sheetAction)
        assertNull(target?.sheetLinkLabel)
    }

    @Test
    fun markWithoutBodyOrActionDoesNotOpen() {
        assertNull(
            sessionFailureDetailTarget(
                text = "Permission rejected",
                providerBody = null,
                action = null,
                actionLabel = null,
            ),
        )
    }

    @Test
    fun liveLinkRidesAlongWithTheBody() {
        val target =
            sessionFailureDetailTarget(
                text = "Slow down",
                providerBody = "limited",
                action = null,
                actionLabel = null,
                linkLabel = " Upgrade ",
                link = " https://example.com ",
            )
        assertEquals("Upgrade", target?.sheetLinkLabel)
        assertEquals("https://example.com", target?.sheetLink)
        assertNull(target?.sheetAction)
    }

    @Test
    fun newestLiveRetryBodySkipsOlderAndUncoveredMarks() {
        val older = failureMessage("1", body = "old", covered = true)
        val newer = failureMessage("2", body = "new", covered = true)
        val auth = failureMessage("3", body = "denied", covered = false)
        assertEquals(
            "new",
            SessionChatTimelineBlocks.newestLiveRetryProviderBody(listOf(older, newer, auth)),
        )
    }

    private fun failureMessage(
        id: String,
        body: String,
        covered: Boolean,
    ) = SessionChatMessageItem(
        id = id,
        role = MessageRole.Assistant,
        text = "",
        metadataLabel = "",
        blocks =
            listOf(
                SessionChatTimelineBlock.FailureLine(
                    blockID = "$id:failure",
                    text = "Slow down",
                    providerBody = body,
                    coveredByLiveRetry = covered,
                ),
            ),
    )
}
