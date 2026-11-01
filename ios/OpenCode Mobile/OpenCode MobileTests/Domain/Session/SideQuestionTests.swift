import Foundation
import Testing
@testable import OpenCode_Mobile

struct SideQuestionTests {
    @Test func promptPutsTheTuiInstructionBeforeTheTrimmedQuestion() {
        #expect(
            SideQuestion.prompt("  What changed?\n") ==
                "The user is asking a quick side question about the conversation so far. "
                + "Answer directly and concisely in markdown from what you already know. "
                + "Do not call any tools and do not take any actions.\n\nWhat changed?"
        )
    }

    @Test func questionInDraftReadsALeadingBtwCommand() {
        #expect(SideQuestion.question(inDraft: "/btw why?") == "why?")
        #expect(SideQuestion.question(inDraft: "  /btw   why? ") == "why?")
        #expect(SideQuestion.question(inDraft: "/btw line one\nline two") == "line one\nline two")
        #expect(SideQuestion.question(inDraft: "/btw") == "")
        #expect(SideQuestion.question(inDraft: "/btw ") == "")
    }

    @Test func questionInDraftIgnoresOtherText() {
        #expect(SideQuestion.question(inDraft: "/btwx why") == nil)
        #expect(SideQuestion.question(inDraft: "ask /btw why") == nil)
        #expect(SideQuestion.question(inDraft: "/build") == nil)
        #expect(SideQuestion.question(inDraft: "") == nil)
    }

    @Test func draftAfterCommandKeepsTheRestAsTyped() {
        #expect(SideQuestion.draftAfterCommand("/btw ") == "")
        #expect(SideQuestion.draftAfterCommand(" /btw  Why ") == "Why ")
        #expect(SideQuestion.draftAfterCommand("/btw") == nil)
        #expect(SideQuestion.draftAfterCommand("Why /btw ") == nil)
    }

    @Test func entersModeOnlyOnceWhitespaceFollowsTheCommand() {
        #expect(!SideQuestion.entersMode("/bt"))
        #expect(!SideQuestion.entersMode("/btw"))
        #expect(SideQuestion.entersMode("/btw "))
        #expect(SideQuestion.entersMode("/btw what changed?"))
        #expect(!SideQuestion.entersMode("/btwx "))
        #expect(!SideQuestion.entersMode("hi /btw "))
    }
}

@MainActor
@Suite(.serialized)
struct SideQuestionStoreTests {
    /// Holds each request until the test answers it.
    private final class Server: @unchecked Sendable {
        private let lock = NSLock()
        private var _requests: [String] = []
        private var replies: [CheckedContinuation<String, Error>] = []

        var requests: [String] { lock.withLock { _requests } }
        var waiting: Int { lock.withLock { replies.count } }

        func ask(_ sessionID: String, _ question: String) async throws -> String {
            try await withCheckedThrowingContinuation { continuation in
                lock.withLock {
                    _requests.append("\(sessionID):\(question)")
                    replies.append(continuation)
                }
            }
        }

        func reply(_ result: Result<String, Error>) {
            let continuation = lock.withLock { replies.removeFirst() }
            continuation.resume(with: result)
        }
    }

    private func waitUntil(_ predicate: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }

    private func makeStore(_ server: Server) -> SideQuestionStore {
        SideQuestionStore { sessionID, question in try await server.ask(sessionID, question) }
    }

    @Test func askRunsOneRequestAndAnswersIntoTheSession() async {
        let server = Server()
        let store = makeStore(server)
        var events: [SideQuestionEvent] = []
        _ = store.observe { events.append($0) }

        let id = store.ask(sessionID: "ses-1", question: "  What changed? ")
        _ = await waitUntil { server.waiting == 1 }

        #expect(server.requests == ["ses-1:What changed?"])
        #expect(store.entry(id: id!)?.status == .pending)

        server.reply(.success("Two files."))
        _ = await waitUntil { store.entries(sessionID: "ses-1").first?.answer != nil }

        #expect(store.entries(sessionID: "ses-1").first?.answer == "Two files.")
        #expect(store.entries(sessionID: "ses-2").isEmpty)
        #expect(events.contains(.answered(sessionID: "ses-1", id: id!)))
    }

    @Test func blankQuestionIsNotAsked() async {
        let server = Server()
        let store = makeStore(server)

        #expect(store.ask(sessionID: "ses-1", question: "  \n") == nil)
        #expect(store.entries(sessionID: "ses-1").isEmpty)
        #expect(server.requests.isEmpty)
    }

    @Test func failureStaysRetryableAndRetryAsksAgain() async {
        let server = Server()
        let store = makeStore(server)
        var events: [SideQuestionEvent] = []
        _ = store.observe { events.append($0) }

        let id = store.ask(sessionID: "ses-1", question: "Why?")!
        _ = await waitUntil { server.waiting == 1 }
        server.reply(.failure(SessionError.http(statusCode: 503, message: "busy")))
        _ = await waitUntil { store.entry(id: id)?.status == .failed }

        #expect(store.entry(id: id)?.status == .failed)
        #expect(events.contains(.failed(sessionID: "ses-1", id: id, question: "Why?", retryable: true, message: "busy")))

        store.retry(id)
        #expect(store.entry(id: id)?.status == .pending)
        _ = await waitUntil { server.waiting == 1 }
        server.reply(.success("Because."))
        _ = await waitUntil { store.entry(id: id)?.answer != nil }

        #expect(server.requests.count == 2)
        #expect(store.entry(id: id)?.answer == "Because.")
    }

    @Test func notFoundIsNotRetryableAndDropsTheEntry() async {
        let server = Server()
        let store = makeStore(server)
        var events: [SideQuestionEvent] = []
        _ = store.observe { events.append($0) }

        let id = store.ask(sessionID: "ses-1", question: "Why?")!
        _ = await waitUntil { server.waiting == 1 }
        server.reply(.failure(SessionError.http(statusCode: 404, message: "Not Found")))
        _ = await waitUntil { store.entry(id: id) == nil }

        #expect(store.entry(id: id) == nil)
        #expect(events.contains(.failed(sessionID: "ses-1", id: id, question: "Why?", retryable: false, message: "Not Found")))
    }

    @Test func removeAndClearDropRunningRequests() async {
        let server = Server()
        let store = makeStore(server)
        var answered = 0
        _ = store.observe { event in
            if case .answered = event { answered += 1 }
        }

        let removed = store.ask(sessionID: "ses-1", question: "One")!
        store.ask(sessionID: "ses-2", question: "Two")
        _ = await waitUntil { server.waiting == 2 }

        store.remove(removed)
        #expect(store.entry(id: removed) == nil)
        #expect(store.entries(sessionID: "ses-2").count == 1)

        store.clearCache()
        server.reply(.success("late"))
        server.reply(.success("late"))
        try? await Task.sleep(for: .milliseconds(50))

        #expect(store.entries(sessionID: "ses-1").isEmpty)
        #expect(store.entries(sessionID: "ses-2").isEmpty)
        #expect(answered == 0)
    }
}
