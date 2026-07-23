package dev.opencode.mobile.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSlashActionCatalogTest {
    @Test
    fun visibleActions_emptySession_showsOnlyNew() {
        val visible =
            ClientSlashActionCatalog.visibleActions(
                ClientSlashActionVisibilityInput(
                    visibleUserMessageCount = 0,
                    hasUndoableUserMessage = false,
                    hasActiveSessionRevert = false,
                    hasSelectedComposerModel = false,
                ),
            )
        assertEquals(listOf(ClientSlashAction.New), visible)
    }

    @Test
    fun visibleActions_userMessageWithoutModel_showsNewForkUndo() {
        val visible =
            ClientSlashActionCatalog.visibleActions(
                ClientSlashActionVisibilityInput(
                    visibleUserMessageCount = 1,
                    hasUndoableUserMessage = true,
                    hasActiveSessionRevert = false,
                    hasSelectedComposerModel = false,
                ),
            )
        assertEquals(
            listOf(ClientSlashAction.New, ClientSlashAction.Fork, ClientSlashAction.Undo),
            visible,
        )
    }

    @Test
    fun visibleActions_userMessageWithModel_includesCompact() {
        val visible =
            ClientSlashActionCatalog.visibleActions(
                ClientSlashActionVisibilityInput(
                    visibleUserMessageCount = 1,
                    hasUndoableUserMessage = true,
                    hasActiveSessionRevert = false,
                    hasSelectedComposerModel = true,
                ),
            )
        assertEquals(
            listOf(
                ClientSlashAction.New,
                ClientSlashAction.Fork,
                ClientSlashAction.Undo,
                ClientSlashAction.Compact,
            ),
            visible,
        )
    }

    @Test
    fun visibleActions_activeRevert_includesRedo() {
        val visible =
            ClientSlashActionCatalog.visibleActions(
                ClientSlashActionVisibilityInput(
                    visibleUserMessageCount = 0,
                    hasUndoableUserMessage = false,
                    hasActiveSessionRevert = true,
                    hasSelectedComposerModel = true,
                ),
            )
        assertEquals(listOf(ClientSlashAction.New, ClientSlashAction.Redo), visible)
    }

    @Test
    fun rowTitle_matchesAllowlistCopy() {
        assertEquals("[new] Start a new session", ClientSlashAction.New.rowTitle)
        assertEquals("[fork] Fork this session", ClientSlashAction.Fork.rowTitle)
        assertEquals("[undo] Undo last message", ClientSlashAction.Undo.rowTitle)
        assertEquals("[redo] Redo undone message", ClientSlashAction.Redo.rowTitle)
        assertEquals("[compact] Compact session context", ClientSlashAction.Compact.rowTitle)
    }

    @Test
    fun visibilityInput_filtersMessagesAtOrAfterRevertBoundary() {
        val input =
            ClientSlashActionCatalog.visibilityInput(
                messages =
                    listOf(
                        ClientSlashMessageRef(id = "u1", isUser = true, isPending = false, isStreaming = false, hasNonEmptyText = true),
                        ClientSlashMessageRef(id = "a1", isUser = false, isPending = false, isStreaming = false, hasNonEmptyText = true),
                        ClientSlashMessageRef(id = "u2", isUser = true, isPending = false, isStreaming = false, hasNonEmptyText = true),
                        ClientSlashMessageRef(id = "a2", isUser = false, isPending = false, isStreaming = false, hasNonEmptyText = true),
                    ),
                revertMessageId = "u2",
                hasSelectedComposerModel = true,
            )
        assertEquals(1, input.visibleUserMessageCount)
        assertTrue(input.hasUndoableUserMessage)
        assertTrue(input.hasActiveSessionRevert)
        assertTrue(input.hasSelectedComposerModel)
    }

    @Test
    fun visibilityInput_pendingUser_doesNotCountAsUndoable() {
        val input =
            ClientSlashActionCatalog.visibilityInput(
                messages =
                    listOf(
                        ClientSlashMessageRef(id = "u1", isUser = true, isPending = true, isStreaming = false, hasNonEmptyText = true),
                    ),
                revertMessageId = null,
                hasSelectedComposerModel = false,
            )
        assertEquals(0, input.visibleUserMessageCount)
        assertFalse(input.hasUndoableUserMessage)
    }

    @Test
    fun visibilityInput_emptyTextUser_countsForForkButNotUndo() {
        val input =
            ClientSlashActionCatalog.visibilityInput(
                messages =
                    listOf(
                        ClientSlashMessageRef(id = "u1", isUser = true, isPending = false, isStreaming = false, hasNonEmptyText = false),
                    ),
                revertMessageId = null,
                hasSelectedComposerModel = true,
            )
        assertEquals(1, input.visibleUserMessageCount)
        assertFalse(input.hasUndoableUserMessage)
    }
}
