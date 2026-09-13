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

    @Test func loadOlderPreservesSseRevisionRace() async throws {
        let store = InMemoryMessageStore()
        let engine = SessionMessageEngine(store: store)
        let slot = MessageSessionKey(connectionProfileID: "local", directory: "/repo", sessionID: "ses-1").storeSlot
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(
                messages: [
                    windowMessage("m2", role: .user, text: "prompt"),
                    Message(
                        id: "m3",
                        role: .assistant,
                        text: "Hello",
                        parts: [.text(TextPart(id: "prt-1", text: "Hello", isStreaming: true))],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ],
                olderCursor: "c1",
                hasOlder: true
            ),
            MessageHistoryPage(
                messages: [
                    windowMessage("m1", role: .user, text: "old"),
                    windowMessage("m2", role: .user, text: "prompt"),
                    Message(
                        id: "m3",
                        role: .assistant,
                        text: "Stale",
                        parts: [.text(TextPart(id: "prt-1", text: "Stale", isStreaming: false))],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ],
                olderCursor: nil,
                hasOlder: false
            ),
        ])
        let sync = MessageSync(
            listMessages: { _, _ in [] },
            listMessagePage: { _, _, query in
                if case .older = query {
                    _ = engine.apply(
                        sessionID: slot,
                        patch: .partDelta(messageID: "m3", partID: "prt-1", delta: " world")
                    )
                }
                return try await pages.page(query: query)
            },
            store: engine,
            eventStream: FakeForegroundEventStream(),
            recoveryDelay: { _ in }
        )
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { collector.latest()?.map(\.id) == ["m2", "m3"] }

        let merged = try await sync.loadOlderMessages(sessionID: "ses-1", directory: "/repo")
        task.cancel()
        #expect(merged.map(\.id) == ["m1", "m2", "m3"])
        #expect(merged.last?.text == "Hello world")
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

    @Test func splitTurnPublishesTailBeforeBackfill() async {
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
        let paintedTail = await waitUntil { collector.first()?.map(\.id) == ["a20"] }
        let loaded = await waitUntil { collector.latest()?.map(\.id) == ["u1", "a20"] }
        task.cancel()
        #expect(paintedTail)
        #expect(loaded)
        #expect(collector.first()?.map(\.id) == ["a20"])
        #expect(pages.queries == [
            .newest(limit: 20),
            .older(cursor: "c1", limit: 20),
        ])
    }

    @Test func refreshBackfillsSplitTurnAfterTail() async throws {
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
        let refreshed = try await sync.refreshMessages(sessionID: "ses-1", directory: "/repo")
        #expect(refreshed.map(\.id) == ["u1", "a20"])
        #expect(pages.queries == [
            .newest(limit: 20),
            .older(cursor: "c1", limit: 20),
        ])
    }

    @Test func pollAndRefreshPreserveFurtherBackOlderCursor() async throws {
        let newest = MessageHistoryPage(
            messages: [windowMessage("m3", role: .user, text: "live")],
            olderCursor: "c-tail",
            hasOlder: true
        )
        let pages = QueryKeyedWindowPages(
            newest: newest,
            older: [
                "c-tail": MessageHistoryPage(
                    messages: [windowMessage("m1", role: .user, text: "old"), windowMessage("m3", role: .user, text: "live")],
                    olderCursor: "c-older",
                    hasOlder: true
                ),
                "c-older": MessageHistoryPage(
                    messages: [windowMessage("m0", role: .user, text: "oldest")],
                    olderCursor: "c-oldest",
                    hasOlder: true
                ),
            ]
        )
        let events = FakeForegroundEventStream()
        events.setConnectionState(.connected)
        let sync = MessageSync(
            listMessages: { _, _ in [] },
            listMessagePage: { _, _, query in
                try await pages.page(query: query)
            },
            store: InMemoryMessageStore(),
            eventStream: events,
            recoveryDelay: { _ in }
        )
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { collector.latest()?.map(\.id) == ["m3"] }

        _ = try await sync.loadOlderMessages(sessionID: "ses-1", directory: "/repo")
        #expect(pages.olderCursors == ["c-tail"])

        _ = try await sync.refreshMessages(sessionID: "ses-1", directory: "/repo")
        _ = try await sync.loadOlderMessages(sessionID: "ses-1", directory: "/repo")
        task.cancel()

        #expect(pages.olderCursors == ["c-tail", "c-older"])
        #expect(sync.hasOlderMessages(sessionID: "ses-1", directory: "/repo"))
    }

    @Test func noOverlapTailReplacesOlderCursor() async throws {
        let pages = QueryKeyedWindowPages(
            newest: MessageHistoryPage(
                messages: [windowMessage("m3", role: .user)],
                olderCursor: "c-tail",
                hasOlder: true
            ),
            older: [
                "c-tail": MessageHistoryPage(
                    messages: [windowMessage("m1", role: .user), windowMessage("m3", role: .user)],
                    olderCursor: "c-older",
                    hasOlder: true
                ),
                "c-reset": MessageHistoryPage(
                    messages: [windowMessage("n0", role: .user)],
                    olderCursor: nil,
                    hasOlder: false
                ),
            ]
        )
        let sync = makeWindowSync(pages: pages, store: InMemoryMessageStore())
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { collector.latest()?.map(\.id) == ["m3"] }
        _ = try await sync.loadOlderMessages(sessionID: "ses-1", directory: "/repo")

        pages.newest = MessageHistoryPage(
            messages: [windowMessage("n1", role: .user), windowMessage("n2", role: .assistant)],
            olderCursor: "c-reset",
            hasOlder: true
        )
        _ = try await sync.refreshMessages(sessionID: "ses-1", directory: "/repo")
        _ = try await sync.loadOlderMessages(sessionID: "ses-1", directory: "/repo")
        task.cancel()

        #expect(pages.olderCursors == ["c-tail", "c-reset"])
        #expect(!sync.hasOlderMessages(sessionID: "ses-1", directory: "/repo"))
    }

    @Test func cacheHitUnchangedFingerprintKeepsOlderWindow() async {
        let cached = [
            windowMessage("m2", role: .user, text: "cached"),
            windowMessage("m3", role: .assistant, text: "tail"),
        ]
        let pages = QueryKeyedWindowPages(
            newest: MessageHistoryPage(messages: cached, olderCursor: "older", hasOlder: true),
            older: [:]
        )
        let sync = MessageSync(
            listMessages: { _, _ in cached },
            listMessagePage: { _, _, query in
                try await pages.page(query: query)
            },
            store: InMemoryMessageStore(),
            eventStream: FakeForegroundEventStream(),
            recoveryDelay: { _ in },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let confirmed = await waitUntil {
            collector.sawCacheSyncConfirmed && sync.hasOlderMessages(sessionID: "ses-1", directory: "/repo")
        }
        task.cancel()
        #expect(confirmed)
        #expect(sync.hasOlderMessages(sessionID: "ses-1", directory: "/repo"))
    }

    @Test func observeUsesBoundedTailQuery() async {
        let pages = WindowPageRepository(pages: [
            MessageHistoryPage(messages: [windowMessage("m1", role: .user)], olderCursor: nil, hasOlder: false),
        ])
        let events = FakeForegroundEventStream()
        events.setConnectionState(.connected)
        let sync = MessageSync(
            listMessages: { _, _ in [] },
            listMessagePage: { _, _, query in
                try await pages.page(query: query)
            },
            store: InMemoryMessageStore(),
            eventStream: events,
            recoveryDelay: { _ in }
        )
        let collector = WindowMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { collector.latest() != nil }
        try? await Task.sleep(for: .milliseconds(40))
        task.cancel()
        #expect(pages.queries == [.newest(limit: 20)])
        #expect(pages.queries.allSatisfy { query in
            if case .newest(let limit) = query { return limit == 20 }
            return true
        })
    }

    private func makeWindowSync(pages: QueryKeyedWindowPages, store: InMemoryMessageStore) -> MessageSync {
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
    private var _sawCacheSyncConfirmed = false

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            lock.lock()
            switch update {
            case .messages(let messages, _):
                emissions.append(messages)
            case .cacheSyncConfirmed:
                _sawCacheSyncConfirmed = true
            default:
                break
            }
            lock.unlock()
        }
    }

    func first() -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        return emissions.first
    }

    func latest() -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        return emissions.last
    }

    var sawCacheSyncConfirmed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _sawCacheSyncConfirmed
    }
}

private final class QueryKeyedWindowPages: @unchecked Sendable {
    private let lock = NSLock()
    private var newestPage: MessageHistoryPage
    private var older: [String: MessageHistoryPage]
    private var queries: [MessageHistoryQuery] = []

    var newest: MessageHistoryPage {
        get {
            lock.lock()
            defer { lock.unlock() }
            return newestPage
        }
        set {
            lock.lock()
            newestPage = newValue
            lock.unlock()
        }
    }

    init(newest: MessageHistoryPage, older: [String: MessageHistoryPage]) {
        newestPage = newest
        self.older = older
    }

    func page(query: MessageHistoryQuery) async throws -> MessageHistoryPage {
        lock.lock()
        defer { lock.unlock() }
        queries.append(query)
        switch query {
        case .newest:
            return newestPage
        case .older(let cursor, _):
            return older[cursor] ?? .complete([])
        }
    }

    var olderCursors: [String] {
        lock.lock()
        defer { lock.unlock() }
        return queries.compactMap { query in
            if case .older(let cursor, _) = query { return cursor }
            return nil
        }
    }

    var newestCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return queries.filter {
            if case .newest = $0 { return true }
            return false
        }.count
    }
}

private final class SeedingSessionCache: SessionCache, @unchecked Sendable {
    private let messages: [Message]

    init(messages: [Message]) {
        self.messages = messages
    }

    func seedSessions(worktree: String) async -> [Session]? { nil }
    func rememberSessions(worktree: String, sessions: [Session]) async {}
    func openMessages(sessionID: String, worktree: String) -> any SessionMessageCacheHandle {
        SeedingSessionMessageCacheHandle(messages: messages)
    }
    func purgeWorktree(serverBaseURL: String, worktree: String) async {}
    func clearAll() async {}
    func estimatedSizeBytes() async -> Int64 { 0 }
}

private final class SeedingSessionMessageCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    private let messages: [Message]

    init(messages: [Message]) {
        self.messages = messages
    }

    func seed() async -> [Message]? { messages }
    func note(messages: [Message]) {}
    func flush() async {}
    func close() {}
}
