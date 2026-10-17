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
