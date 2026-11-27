import Foundation
import Testing
@testable import OpenCode_Mobile

/// Spoken phrases come from the String Catalog with positional arguments (#886).
struct AccessibilityPhraseTextTests {
    @Test func todoItemSpeaksTextThenStatus() {
        #expect(OpenCodeSessionTodoItem.accessibilityLabel(content: "Summarize README", status: .pending) == "Summarize README, pending")
        #expect(OpenCodeSessionTodoItem.accessibilityLabel(content: "Summarize README", status: .inProgress) == "Summarize README, in progress")
        #expect(OpenCodeSessionTodoItem.accessibilityLabel(content: "Summarize README", status: .completed) == "Summarize README, completed")
        #expect(OpenCodeSessionTodoItem.accessibilityLabel(content: "Summarize README", status: .cancelled) == "Summarize README, cancelled")
    }

    @Test func todosPillSpeaksProgressAndTheActiveTodo() {
        #expect(sessionTodosPillAccessibilityLabel(completedCount: 1, totalCount: 3, activePreview: nil) == "Session todos, 1 of 3 completed")
        #expect(sessionTodosPillAccessibilityLabel(completedCount: 1, totalCount: 3, activePreview: "") == "Session todos, 1 of 3 completed")
        #expect(
            sessionTodosPillAccessibilityLabel(completedCount: 2, totalCount: 5, activePreview: "Write tests")
                == "Session todos, 2 of 5 completed, Write tests"
        )
    }

    @Test func reasoningRowLabel() {
        #expect(OpenCodeReasoningRow.label(state: .working, durationLabel: "4s") == "Thinking")
        #expect(OpenCodeReasoningRow.label(state: .idle, durationLabel: "4s") == "Thought")
        #expect(OpenCodeReasoningRow.label(state: .expanded, durationLabel: nil) == "Thought")
        #expect(OpenCodeReasoningRow.label(state: .expanded, durationLabel: "4s") == "Thought · 4s")
    }

    @Test func unifiedDiffRowSpeaksKindLinesAndText() {
        #expect(
            OpenCodeUnifiedDiff.accessibilityLabel(UnifiedDiffRow(id: 1, kind: .addition, oldLine: nil, newLine: 12, text: "let x = 1"))
                == "addition, new line 12, let x = 1"
        )
        #expect(
            OpenCodeUnifiedDiff.accessibilityLabel(UnifiedDiffRow(id: 2, kind: .context, oldLine: 3, newLine: 4, text: "}"))
                == "context, old line 3, new line 4, }"
        )
        #expect(
            OpenCodeUnifiedDiff.accessibilityLabel(UnifiedDiffRow(id: 3, kind: .hunk, oldLine: nil, newLine: nil, text: "@@ -1 +1 @@"))
                == "hunk, , @@ -1 +1 @@"
        )
    }

    @Test func floatingPillStateWordsAndDismiss() {
        #expect(OpenCodeFloatingPillState.allCases.map(\.spokenName) == ["In progress", "Ready", "Needs attention", "Error", "Done"])
        #expect(OpenCodeFloatingPillStyle.closeAccessibilityLabel(layout: .label("+3")) == "Dismiss +3")
        #expect(OpenCodeFloatingPillStyle.closeAccessibilityLabel(layout: .label("/btw"), override: "Side question failed") == "Dismiss Side question failed")
    }

    @Test func attachmentSpeaksNameThenDetail() {
        #expect(OpenCodeAttachmentChip.accessibilityLabel(filename: "notes.txt", detail: "Text") == "notes.txt, Text")
    }

    @Test func disclosureWords() {
        #expect(String(localized: "common.expanded", defaultValue: "Expanded") == "Expanded")
        #expect(String(localized: "common.collapsed", defaultValue: "Collapsed") == "Collapsed")
    }
}
