import Foundation
import Testing
@testable import OpenCode_Mobile

struct WorkingTurnIndicatorTests {
    @Test func labelForActiveTurn_nilWhenIdle() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: nil,
                messages: [user("u1")]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsWorkingAfterUserWithNoAssistant() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1")]
            ) == "Working"
        )
    }

    @Test func labelForActiveTurn_hidesWhenAssistantHasText() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "Hello")]
            ) == nil
        )
    }

    private func user(_ id: String) -> SessionChatRow {
        SessionChatRow(id: id, role: .user, text: "Hi", metadataLabel: "")
    }

    private func assistant(_ id: String, text: String) -> SessionChatRow {
        SessionChatRow(id: id, role: .assistant, text: text, metadataLabel: "")
    }
}
