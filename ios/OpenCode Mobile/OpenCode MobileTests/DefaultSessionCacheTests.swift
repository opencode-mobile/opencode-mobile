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
                            dataURL: "data:image/png;base64,abc"
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
        #expect(encoded.contains("\"schemaVersion\":1"))
    }
}
