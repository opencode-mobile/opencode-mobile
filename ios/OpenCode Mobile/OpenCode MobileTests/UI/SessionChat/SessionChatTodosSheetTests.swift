import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatTodosSheetTests {
    @Test func progressPieces_splitsTheEnglishPattern() {
        #expect(SessionChatTodosSheetModifier.progressPieces("%1$lld of %2$lld completed") == [
            .count(0), .literal(" of "), .count(1), .literal(" completed"),
        ])
    }

    @Test func progressPieces_followsATranslatedOrder() {
        #expect(SessionChatTodosSheetModifier.progressPieces("Done: %2$lld / %1$lld") == [
            .literal("Done: "), .count(1), .literal(" / "), .count(0),
        ])
    }

    @Test func progressPieces_takesUnnumberedSlotsInOrderAndKeepsPercent() {
        #expect(SessionChatTodosSheetModifier.progressPieces("%lld of %lld (100%%)") == [
            .count(0), .literal(" of "), .count(1), .literal(" (100%)"),
        ])
    }

    @Test func progressPattern_comesFromTheCatalog() {
        let pattern = Bundle.main.localizedString(forKey: "session_chat_sheet.todos.progress", value: nil, table: nil)
        #expect(pattern == "%1$lld of %2$lld completed")
    }
}
