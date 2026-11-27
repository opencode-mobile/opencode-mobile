package dev.opencode.mobile.ui.components

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.EnglishStrings
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spoken phrases come from the string files with positional arguments (#886). */
class AccessibilityPhraseTextTest {
    @Test
    fun todoItemSpeaksTextThenStatus() {
        fun label(status: OpenCodeSessionTodoItemStatus) =
            UiText
                .resource(
                    R.string.component_todo_item_accessibility_label,
                    "Summarize README",
                    UiText.resource(status.accessibilityPhrase),
                ).english()
        assertEquals("Summarize README, pending", label(OpenCodeSessionTodoItemStatus.Pending))
        assertEquals("Summarize README, in progress", label(OpenCodeSessionTodoItemStatus.InProgress))
        assertEquals("Summarize README, completed", label(OpenCodeSessionTodoItemStatus.Completed))
        assertEquals("Summarize README, cancelled", label(OpenCodeSessionTodoItemStatus.Cancelled))
    }

    @Test
    fun todosPillSpeaksProgressAndTheActiveTodo() {
        assertEquals("Session todos, 1 of 3 completed", sessionTodosPillAccessibilityLabel(1, 3, null).english())
        assertEquals("Session todos, 1 of 3 completed", sessionTodosPillAccessibilityLabel(1, 3, " ").english())
        assertEquals(
            "Session todos, 2 of 5 completed, Write tests",
            sessionTodosPillAccessibilityLabel(2, 5, "Write tests").english(),
        )
    }

    @Test
    fun reasoningRowLabel() {
        assertEquals("Thinking", reasoningLabel(OpenCodeChatActivityState.Working, "4s").english())
        assertEquals("Thought", reasoningLabel(OpenCodeChatActivityState.Idle, "4s").english())
        assertEquals("Thought", reasoningLabel(OpenCodeChatActivityState.Expanded, null).english())
        assertEquals("Thought · 4s", reasoningLabel(OpenCodeChatActivityState.Expanded, "4s").english())
    }

    @Test
    fun unifiedDiffRowSpeaksKindLinesAndText() {
        assertEquals(
            "addition, new line 12, let x = 1",
            UnifiedDiffRow(1, UnifiedDiffRowKind.Addition, null, 12, "let x = 1").accessibilityLabel(EnglishStrings),
        )
        assertEquals(
            "context, old line 3, new line 4, }",
            UnifiedDiffRow(2, UnifiedDiffRowKind.Context, 3, 4, "}").accessibilityLabel(EnglishStrings),
        )
        assertEquals(
            "hunk, @@ -1 +1 @@",
            UnifiedDiffRow(3, UnifiedDiffRowKind.Hunk, null, null, "@@ -1 +1 @@").accessibilityLabel(EnglishStrings),
        )
    }

    @Test
    fun disclosureAndAttachmentWords() {
        assertEquals("Expanded", UiText.resource(R.string.common_expanded).english())
        assertEquals("Collapsed", UiText.resource(R.string.common_collapsed).english())
        assertEquals(
            "notes.txt, TEXT",
            UiText.resource(R.string.component_attachment_accessibility_label, "notes.txt", "TEXT").english(),
        )
    }
}
