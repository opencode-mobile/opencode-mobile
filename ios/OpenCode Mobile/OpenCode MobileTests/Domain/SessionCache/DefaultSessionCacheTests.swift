import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct DefaultSessionCacheTests {
    private let connectionId = "http://localhost:4096/|opencode"

    @Test func seedAndRememberSessionsRoundTrip() async {
        let store = InMemorySessionCacheStore()
        let cache = DefaultSessionCache(
            store: store,
            connectionIdProvider: { connectionId },
            clock: { 1_000 }
        )
        let sessions = [
            Session(
                id: "s1",
                title: "One",
                slug: nil,
                projectID: nil,
                directory: nil,
                parentID: nil,
                updatedAtEpochMs: nil
            ),
        ]
        await cache.rememberSessions(worktree: "/repo", sessions: sessions)
        let seeded = await cache.seedSessions(worktree: "/repo")
        #expect(seeded == sessions)
    }

    @Test func projectResolutionRoundTripsPerConnection() async {
        let store = InMemorySessionCacheStore()
        let cache = DefaultSessionCache(store: store, connectionIdProvider: { connectionId })
        let resolution = HomeProjectResolution(id: "prj_repo", canonical: "/repo", name: "Repo", isRepository: true)
        cache.rememberProjectResolution(worktree: "/repo", resolution: resolution)

        #expect(await seededResolution(cache, worktree: "/repo", connectionID: nil) { $0 == resolution } == resolution)
        #expect(await cache.seedProjectResolution(worktree: "/repo", connectionID: connectionId) == resolution)
        #expect(await cache.seedProjectResolution(worktree: "/repo", connectionID: "other") == nil)

        await cache.purgeWorktree(serverBaseURL: "http://localhost:4096/", worktree: "/repo")
        #expect(await cache.seedProjectResolution(worktree: "/repo", connectionID: nil) == nil)
    }

    @Test func projectResolutionKeepsTheProfileActiveWhenItWasRemembered() async {
        let profile = ActiveProfileBox("profile-a")
        let cache = DefaultSessionCache(store: InMemorySessionCacheStore(), connectionIdProvider: { profile.id })
        let resolution = HomeProjectResolution(id: "prj_a", canonical: "/repo", name: nil, isRepository: true)
        cache.rememberProjectResolution(worktree: "/repo", resolution: resolution)
        profile.id = "profile-b"

        #expect(await seededResolution(cache, worktree: "/repo", connectionID: "profile-a") { $0 != nil } == resolution)
        #expect(await cache.seedProjectResolution(worktree: "/repo", connectionID: "profile-b") == nil)
    }

    @Test func purgeDropsAProjectResolutionStillQueued() async {
        let cache = DefaultSessionCache(store: InMemorySessionCacheStore(), connectionIdProvider: { connectionId })
        cache.rememberProjectResolution(
            worktree: "/repo",
            resolution: HomeProjectResolution(id: "prj_a", canonical: "/repo", name: nil, isRepository: false)
        )
        await cache.purgeWorktree(serverBaseURL: "http://localhost:4096/", worktree: "/repo")
        try? await Task.sleep(for: .milliseconds(50))

        #expect(await cache.seedProjectResolution(worktree: "/repo", connectionID: nil) == nil)
    }

    @Test func projectResolutionsLandInTheOrderTheyWereRemembered() async {
        let store = FirstCommitGatedStore()
        let cache = DefaultSessionCache(store: store, connectionIdProvider: { connectionId })
        let older = HomeProjectResolution(id: "prj_old", canonical: "/repo", name: nil, isRepository: false)
        let newer = HomeProjectResolution(id: "prj_new", canonical: "/repo", name: nil, isRepository: false)
        cache.rememberProjectResolution(worktree: "/repo", resolution: older)
        for _ in 0 ..< 200 where await store.commits == 0 {
            try? await Task.sleep(for: .milliseconds(5))
        }
        cache.rememberProjectResolution(worktree: "/repo", resolution: newer)
        await store.open()

        #expect(await seededResolution(cache, worktree: "/repo", connectionID: nil) { $0 == newer } == newer)
        try? await Task.sleep(for: .milliseconds(50))
        #expect(await cache.seedProjectResolution(worktree: "/repo", connectionID: nil) == newer)
    }

    /// Polls until `done` holds for the seeded resolution, or about two seconds pass.
    private func seededResolution(
        _ cache: DefaultSessionCache,
        worktree: String,
        connectionID: String?,
        until done: (HomeProjectResolution?) -> Bool
    ) async -> HomeProjectResolution? {
        var seeded = await cache.seedProjectResolution(worktree: worktree, connectionID: connectionID)
        for _ in 0 ..< 200 where !done(seeded) {
            try? await Task.sleep(for: .milliseconds(10))
            seeded = await cache.seedProjectResolution(worktree: worktree, connectionID: connectionID)
        }
        return seeded
    }

    @Test func seedSessionsNoopsWithoutConnection() async {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { nil }
        )
        let seeded = await cache.seedSessions(worktree: "/repo")
        #expect(seeded == nil)
    }

    @Test func messageHandleSeedsAfterDebouncedFlush() async throws {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { connectionId },
            clock: { 2_000 },
            debounceMilliseconds: 30
        )
        let handle = cache.openMessages(sessionID: "s1", worktree: "/repo")
        let messages = [
            Message(
                id: "m1",
                role: .assistant,
                text: "hello",
                parts: [
                    .text(TextPart(text: "hello", isStreaming: false)),
                    .tool(ToolPart(toolName: "bash", status: .completed, title: "done")),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        handle.note(messages: messages)
        // Immediate seed should miss — debounce has not written yet.
        #expect(await handle.seed() == nil)

        var seeded: [Message]?
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 20_000_000)
            seeded = await handle.seed()
            if seeded != nil { break }
        }
        #expect(seeded == messages)
    }

    @Test func flushWritesImmediately() async {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { connectionId },
            debounceMilliseconds: 60_000
        )
        let handle = cache.openMessages(sessionID: "s1", worktree: "/repo")
        let messages = [
            Message(
                id: "m1",
                role: .user,
                text: "hi",
                parts: [],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        handle.note(messages: messages)
        // Allow note()'s lock work to schedule before flush.
        await Task.yield()
        await handle.flush()
        let seeded = await handle.seed()
        #expect(seeded == messages)
    }

    @Test func purgeWorktreeRemovesRows() async {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { connectionId }
        )
        await cache.rememberSessions(
            worktree: "/repo",
            sessions: [
                Session(
                    id: "s1",
                    title: "One",
                    slug: nil,
                    projectID: nil,
                    directory: nil,
                    parentID: nil,
                    updatedAtEpochMs: nil
                ),
            ]
        )
        await cache.purgeWorktree(serverBaseURL: "http://localhost:4096", worktree: "/repo")
        let seeded = await cache.seedSessions(worktree: "/repo")
        #expect(seeded == nil)
        let size = await cache.estimatedSizeBytes()
        #expect(size == 0)
    }

    @Test func purgeWorktreeRemovesRowsKeyedByProfileId() async {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { "profile-abc" }
        )
        await cache.rememberSessions(
            worktree: "/repo",
            sessions: [
                Session(
                    id: "s1",
                    title: "One",
                    slug: nil,
                    projectID: nil,
                    directory: nil,
                    parentID: nil,
                    updatedAtEpochMs: nil
                ),
            ]
        )
        await cache.purgeWorktree(serverBaseURL: "http://localhost:4096", worktree: "/repo")
        let seeded = await cache.seedSessions(worktree: "/repo")
        #expect(seeded == nil)
        let size = await cache.estimatedSizeBytes()
        #expect(size == 0)
    }

    @Test func codecRoundTripsCurrentMessageParts() throws {
        let messages = [
            Message(
                id: "m1",
                role: .user,
                text: "file",
                parts: [
                    .file(
                        FilePart(
                            id: "f1",
                            filename: "a.png",
                            mime: "image/png",
                            kind: .image,
                            dataURL: ""
                        )
                    ),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let encoded = try SessionCacheCodec.encodeMessages(messages)
        let decoded = SessionCacheCodec.decodeMessages(encoded)
        #expect(decoded == messages)
        #expect(!encoded.contains("data:image/png"))
        #expect(encoded.contains("\"schemaVersion\":1"))
    }

    @Test func codecRoundTripsTimelineNotices() throws {
        let kinds: [TimelineNoticeKind] = [
            .moved(directory: "/repo/wt"),
            .agentChanged(agent: "plan", previous: "build"),
            .agentChanged(agent: "plan", previous: nil),
            .modelChanged(
                model: TimelineNoticeModel(providerID: "openai", modelID: "gpt-5", variant: "high"),
                previous: TimelineNoticeModel(providerID: "openai", modelID: "gpt-5")
            ),
            .modelChanged(model: TimelineNoticeModel(providerID: nil, modelID: "gpt-5"), previous: nil),
            .instructionsUpdated(sources: ["AGENTS.md", "CLAUDE.md"]),
            .systemNote("Skills reloaded"),
            .restart,
        ]
        let messages = kinds.enumerated().map { index, kind in
            Message(
                id: "n\(index)",
                role: .notice,
                text: "",
                parts: [.notice(TimelineNotice(id: "n\(index)", kind: kind))],
                model: nil,
                mode: nil,
                sentAtEpochMs: Int64(index)
            )
        }
        let decoded = SessionCacheCodec.decodeMessages(try SessionCacheCodec.encodeMessages(messages))
        #expect(decoded == messages)
    }

    @Test func activityRoundTripPerDirectory() async {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { connectionId },
            clock: { 1_000 },
            activityDebounceMilliseconds: 0
        )
        let entries = [
            SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1_000),
            SessionActivityCacheEntry(
                sessionId: "ses-2",
                activity: .retrying(
                    attempt: 2,
                    nextAtMs: nil,
                    message: nil,
                    errorType: nil,
                    actionLabel: nil,
                    actionLink: nil
                ),
                observedAtMs: 1_000
            ),
        ]
        await cache.rememberActivity(directory: "/repo", entries: entries)
        let seeded = await cache.seedActivity(directory: "/repo")
        #expect(seeded == entries)
        #expect(await cache.seedActivity(directory: "/other") == nil)
    }

    @Test func activitySeedDropsEntriesOlderThanSixHours() async {
        let now = SessionActivityPolicy.cachedActivityTtlMs + 5_000
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { connectionId },
            clock: { now },
            activityDebounceMilliseconds: 0
        )
        await cache.rememberActivity(
            directory: "/repo",
            entries: [
                SessionActivityCacheEntry(sessionId: "fresh", activity: .running, observedAtMs: now - 1_000),
                SessionActivityCacheEntry(sessionId: "stale", activity: .running, observedAtMs: 0),
            ]
        )
        let seeded = await cache.seedActivity(directory: "/repo")
        #expect(seeded?.map(\.sessionId) == ["fresh"])
    }

    @Test func activityClearAllAndPurgeWorktreeDropRows() async {
        let cache = DefaultSessionCache(
            store: InMemorySessionCacheStore(),
            connectionIdProvider: { connectionId },
            activityDebounceMilliseconds: 0
        )
        await cache.rememberActivity(
            directory: "/repo",
            entries: [SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1)]
        )
        await cache.purgeWorktree(serverBaseURL: "http://localhost:4096", worktree: "/repo")
        #expect(await cache.seedActivity(directory: "/repo") == nil)

        await cache.rememberActivity(
            directory: "/repo",
            entries: [SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1)]
        )
        await cache.clearAll()
        #expect(await cache.seedActivity(directory: "/repo") == nil)
    }

    @Test func purgeConnectionCancelsAPendingActivityFlush() async throws {
        let store = InMemorySessionCacheStore()
        var active = "http://203.0.113.5:4096/|opencode"
        let cache = DefaultSessionCache(
            store: store,
            connectionIdProvider: { active },
            activityDebounceMilliseconds: 40
        )
        let entries = [SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1)]
        await cache.rememberActivity(directory: "/repo", entries: entries)
        await cache.purgeConnection(profileID: "removed", serverBaseURL: "http://203.0.113.5:4096/")
        active = "http://192.168.0.2:4096/|opencode"
        try await Task.sleep(nanoseconds: 120_000_000)
        let key = SessionCacheKey(connectionId: active, worktree: "/repo", kind: .sessionActivity)
        #expect(await store.load(key: key) == nil)
    }

    @Test func activityRememberDebouncesAndSkipsUnchanged() async throws {
        let store = InMemorySessionCacheStore()
        var now: Int64 = 1_000
        let cache = DefaultSessionCache(
            store: store,
            connectionIdProvider: { connectionId },
            clock: { now },
            activityDebounceMilliseconds: 40
        )
        let entries = [SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1_000)]
        await cache.rememberActivity(directory: "/repo", entries: entries)
        #expect(await cache.seedActivity(directory: "/repo") == nil)

        var seeded: [SessionActivityCacheEntry]?
        for _ in 0..<40 {
            try await Task.sleep(nanoseconds: 20_000_000)
            seeded = await cache.seedActivity(directory: "/repo")
            if seeded != nil { break }
        }
        #expect(seeded == entries)

        let key = SessionCacheKey(connectionId: connectionId, worktree: "/repo", kind: .sessionActivity)
        let first = await store.load(key: key)
        now = 2_000
        await cache.rememberActivity(directory: "/repo", entries: entries)
        try await Task.sleep(nanoseconds: 80_000_000)
        let second = await store.load(key: key)
        #expect(first?.syncedAtEpochMs == second?.syncedAtEpochMs)
    }
}

private final class ActiveProfileBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: String

    init(_ value: String) { self.value = value }

    var id: String {
        get { lock.withLock { value } }
        set { lock.withLock { value = newValue } }
    }
}

/// Holds the first commit until `open()`, so a newer write queues behind a slow one.
private actor FirstCommitGatedStore: SessionCacheStore {
    private let delegate = InMemorySessionCacheStore()
    private var isOpen = false
    private var gate: CheckedContinuation<Void, Never>?
    private(set) var commits = 0

    func open() {
        isOpen = true
        gate?.resume()
        gate = nil
    }

    func load(key: SessionCacheKey) async -> SessionCacheRecord? { await delegate.load(key: key) }

    func commit(key: SessionCacheKey, value: SessionCacheValue, meta: SessionCacheCommitMeta) async {
        commits += 1
        if commits == 1, !isOpen {
            await withCheckedContinuation { gate = $0 }
        }
        await delegate.commit(key: key, value: value, meta: meta)
    }

    func retain(key: SessionCacheKey, reason: SessionCacheRetainReason, atEpochMs: Int64) async {
        await delegate.retain(key: key, reason: reason, atEpochMs: atEpochMs)
    }

    func evict(_ selector: SessionCacheEvictSelector) async { await delegate.evict(selector) }

    func report(_ selector: SessionCacheEvictSelector) async -> SessionCacheReport { await delegate.report(selector) }
}
