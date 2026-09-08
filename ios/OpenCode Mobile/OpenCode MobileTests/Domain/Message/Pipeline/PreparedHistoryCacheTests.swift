import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct PreparedHistoryCacheTests {
    @Test func evictsLeastRecentlyUsedWhenSessionCapExceeded() {
        let cache = PreparedHistoryCache(characterBudget: 1_000_000, sessionEntryCap: 2)
        let profile = "profile-a"
        cache.setMessages(
            ["m1": display("m1", text: "one")],
            for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-1")
        )
        cache.setMessages(
            ["m2": display("m2", text: "two")],
            for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-2")
        )
        // Touch ses-1 so ses-2 becomes the LRU victim when ses-3 is inserted.
        _ = cache.messages(
            for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-1")
        )
        cache.setMessages(
            ["m3": display("m3", text: "three")],
            for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-3")
        )

        #expect(cache.entryCount == 2)
        #expect(
            cache.messages(
                for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-2")
            ) == nil
        )
        #expect(
            cache.messages(
                for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-1")
            ) != nil
        )
        #expect(
            cache.messages(
                for: PreparedHistoryCacheKey(connectionProfileID: profile, directory: "/a", sessionID: "ses-3")
            ) != nil
        )
    }

    @Test func handleMemoryPressure_clearsEntries() {
        let cache = PreparedHistoryCache(characterBudget: 1_000_000, sessionEntryCap: 4)
        let key = PreparedHistoryCacheKey(connectionProfileID: "profile-a", directory: "/a", sessionID: "ses-1")
        cache.setMessages(["m1": display("m1", text: "one")], for: key)
        #expect(cache.entryCount == 1)
        cache.handleMemoryPressure()
        #expect(cache.entryCount == 0)
        #expect(cache.messages(for: key) == nil)
    }

    @Test func isolatesEntriesByProfileAndDirectory() {
        let cache = PreparedHistoryCache()
        let keyA = PreparedHistoryCacheKey(connectionProfileID: "a", directory: "/one", sessionID: "ses-1")
        let keyB = PreparedHistoryCacheKey(connectionProfileID: "b", directory: "/one", sessionID: "ses-1")
        let keyC = PreparedHistoryCacheKey(connectionProfileID: "a", directory: "/two", sessionID: "ses-1")
        cache.setMessages(["m": display("m", text: "a")], for: keyA)
        cache.setMessages(["m": display("m", text: "b")], for: keyB)
        cache.setMessages(["m": display("m", text: "c")], for: keyC)

        #expect(cache.messages(for: keyA)?["m"]?.text == "a")
        #expect(cache.messages(for: keyB)?["m"]?.text == "b")
        #expect(cache.messages(for: keyC)?["m"]?.text == "c")
    }

    private func display(_ id: String, text: String) -> DisplayMessage {
        DisplayMessage(
            message: Message(
                id: id,
                role: .assistant,
                text: text,
                parts: [.text(TextPart(text: text, isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            renderedMarkdown: nil
        )
    }
}
