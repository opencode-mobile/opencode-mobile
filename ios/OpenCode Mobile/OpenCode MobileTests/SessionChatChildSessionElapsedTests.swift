import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatChildSessionElapsedTests {
    @Test func format_omitsWhenStartMissing() {
        #expect(SessionChatChildSessionElapsed.format(startEpochMs: nil, nowEpochMs: 1_000) == nil)
    }

    @Test func format_usesMinutesAndZeroPaddedSeconds() {
        let start: Int64 = 1_000_000
        #expect(SessionChatChildSessionElapsed.format(startEpochMs: start, nowEpochMs: start) == "0:00")
        #expect(SessionChatChildSessionElapsed.format(startEpochMs: start, nowEpochMs: start + 5_000) == "0:05")
        #expect(SessionChatChildSessionElapsed.format(startEpochMs: start, nowEpochMs: start + 70_000) == "1:10")
        #expect(SessionChatChildSessionElapsed.format(startEpochMs: start, nowEpochMs: start + 3_600_000) == "60:00")
    }

    @Test func format_clampsNegativeElapsedToZero() {
        #expect(SessionChatChildSessionElapsed.format(startEpochMs: 5_000, nowEpochMs: 1_000) == "0:00")
    }

    @Test func letter_usesFirstCharacterUppercased() {
        #expect(SessionChatChildSessionElapsed.letter(from: "explore") == "E")
        #expect(SessionChatChildSessionElapsed.letter(from: "Task") == "T")
        #expect(SessionChatChildSessionElapsed.letter(from: "") == "?")
    }
}
