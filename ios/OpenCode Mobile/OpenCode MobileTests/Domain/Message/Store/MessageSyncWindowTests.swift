import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct MessageSyncWindowTests {
    @Test func bindFetchesNewestTailAndPreservesOlderCache() async {
        let store = InMemoryMessageStore()
        let slot = MessageSessionKey(connectionProfileID: "local", directory: "/repo", sessionID: "ses-1").storeSlot
        store.replaceAll(sessionID: slot, messages: [
            windowMessage("m1", role: .user, text: "older"),
            windowMessage("m2", role: .user, text: "cached"),
            windowMessage("m3", role: .assistant, text: "tail"),
        ])
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(
                messages: [
                    windowMessage("m2", role: .user, text: "cached"),
                    windowMessage("m3", role: .assistant, text: "tail"),
                ],
                olderCursor: "older",
                hasOlder: true
            ),
        ])
        let sync = makeWindowSync(pages: pages, store: store)
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }

        let loaded = await waitUntil { collector.latest()?.count == 3 }
        task.cancel()
        #expect(loaded)
        #expect(collector.latest()?.map(\.id) == ["m1", "m2", "m3"])
        #expect(sync.hasOlderMessages(sessionID: "ses-1", directory: "/repo"))
        #expect(pages.queries == [.newest(limit: 20)])
    }

    @Test func noOverlapTailResetsInsteadOfConcatenating() async {
        let store = InMemoryMessageStore()
        store.replaceAll(sessionID: "ses-1", messages: [
            windowMessage("old-1", role: .user),
            windowMessage("old-2", role: .assistant),
        ])
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(
                messages: [windowMessage("new-1", role: .user), windowMessage("new-2", role: .assistant)],
                olderCursor: nil,
                hasOlder: false
            ),
        ])
        let sync = makeWindowSync(pages: pages, store: store)
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }

        let loaded = await waitUntil { collector.latest()?.first?.id == "new-1" }
        task.cancel()
        #expect(loaded)
        #expect(collector.latest()?.map(\.id) == ["new-1", "new-2"])
    }

    @Test func loadOlderPrependsWithoutOverwritingNewer() async throws {
        let store = InMemoryMessageStore()
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(
                messages: [windowMessage("m3", role: .user, text: "live")],
                olderCursor: "c1",
                hasOlder: true
            ),
            MessageHistoryPage(
                messages: [
                    windowMessage("m1", role: .user, text: "old"),
                    windowMessage("m3", role: .user, text: "stale"),
                ],
                olderCursor: nil,
                hasOlder: false
            ),
        ])
        let sync = makeWindowSync(pages: pages, store: store)
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { collector.latest()?.map(\.id) == ["m3"] }

        let merged = try await sync.loadOlderMessages(sessionID: "ses-1", directory: "/repo")
        task.cancel()
        #expect(merged.map(\.id) == ["m1", "m3"])
        #expect(merged.first { $0.id == "m3" }?.text == "live")
        #expect(!sync.hasOlderMessages(sessionID: "ses-1", directory: "/repo"))
        #expect(pages.queries == [
            .newest(limit: 20),
            .older(cursor: "c1", limit: 200),
        ])
    }

    @Test func splitTurnBackfillFetchesOlderTwentyMessagePages() async {
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(
                messages: [windowMessage("a20", role: .assistant)],
                olderCursor: "c1",
                hasOlder: true
            ),
            MessageHistoryPage(
                messages: [windowMessage("u1", role: .user), windowMessage("a20", role: .assistant)],
                olderCursor: "c0",
                hasOlder: true
            ),
        ])
        let sync = makeWindowSync(pages: pages, store: InMemoryMessageStore())
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.role == .user }
        task.cancel()
        #expect(loaded)
        #expect(collector.latest()?.map(\.id) == ["u1", "a20"])
        #expect(pages.queries == [
            .newest(limit: 20),
            .older(cursor: "c1", limit: 20),
        ])
    }

    @Test func pollUsesBoundedTailQuery() async {
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(messages: [windowMessage("m1", role: .user)], olderCursor: nil, hasOlder: false),
        ])
        let events = FakeForegroundEventStream()
        events.setConnectionState(.disconnected)
        let sync = MessageSync(
            listMessages: { _, _ in [] },
            listMessagePage: { _, _, query in
                try await pages.page(query: query)
            },
            store: InMemoryMessageStore(),
            eventStream: events,
            pollIntervalMs: 20,
            recoveryDelay: { _ in }
        )
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { collector.latest() != nil }
        _ = await waitUntil { pages.queries.filter { $0 == .newest(limit: 20) }.count >= 2 }
        task.cancel()
        #expect(pages.queries.allSatisfy { query in
            if case .newest(let limit) = query { return limit == 20 }
            return true
        })
    }

    private func makeWindowSync(pages: WindowPageRepository, store: InMemoryMessageStore) -> MessageSync {
        MessageSync(
            listMessages: { _, _ in [] },
            listMessagePage: { _, _, query in
                try await pages.page(query: query)
            },
            store: store,
            eventStream: FakeForegroundEventStream(),
            recoveryDelay: { _ in }
        )
    }

    private func waitUntil(
        timeoutNanoseconds: UInt64 = 2_000_000_000,
        _ condition: @escaping @MainActor () -> Bool
    ) async -> Bool {
        let deadline = DispatchTime.now().uptimeNanoseconds + timeoutNanoseconds
        while DispatchTime.now().uptimeNanoseconds < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
    }
}

private func windowMessage(_ id: String, role: MessageRole, text: String = "text") -> Message {
    Message(id: id, role: role, text: text, model: nil, mode: nil, sentAtEpochMs: nil)
}

private final class WindowPageRepository: @unchecked Sendable {
    private let lock = NSLock()
    private var remaining: [MessageHistoryPage]
    private(set) var queries: [MessageHistoryQuery] = []

    init(pages: [MessageHistoryPage]) {
        remaining = pages
    }

    func page(query: MessageHistoryQuery) async throws -> MessageHistoryPage {
        lock.lock()
        queries.append(query)
        let page: MessageHistoryPage
        if remaining.count > 1 {
            page = remaining.removeFirst()
        } else if let last = remaining.first {
            page = last
        } else {
            page = .complete([])
        }
        lock.unlock()
        return page
    }
}

private final class WindowMessageCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var emissions: [[Message]] = []

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            if case .messages(let messages, _) = update {
                lock.lock()
                emissions.append(messages)
                lock.unlock()
            }
        }
    }

    func latest() -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        return emissions.last
    }
}
