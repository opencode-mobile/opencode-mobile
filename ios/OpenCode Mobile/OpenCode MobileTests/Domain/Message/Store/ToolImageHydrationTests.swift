import Foundation
import Testing
@testable import OpenCode_Mobile

/// A session reopened from cache shows the images a `read` tool returned once the server
/// confirms the tail; the cached copy carries only image metadata and a thumbnail.
@Suite(.serialized)
@MainActor
struct ToolImageHydrationTests {
    private static let thumbnail = Data([0xFF, 0xD8, 0xFF, 0xD9])

    // MARK: - Tail identity

    @Test func imageLessCacheDoesNotMatchAPageWithImages() {
        let cached = [readTurn(images: [])]
        let fetched = [readTurn(images: [fullImage()])]
        #expect(!MessageTailIdentity.matches(cached: cached, fetched: fetched))
    }

    @Test func thumbnailOnlyCacheMatchesThePageWithFullImages() {
        let cached = [readTurn(images: [thumbnailImage()])]
        let fetched = [readTurn(images: [fullImage()])]
        #expect(MessageTailIdentity.matches(cached: cached, fetched: fetched))
    }

    // MARK: - Confirm hydrates

    @Test func tailConfirmHydratesFullImagesFromTheFetchedPage() async {
        let cached = [readTurn(images: [thumbnailImage()])]
        let fetched = [readTurn(images: [fullImage()])]
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let sync = MessageSync(
            listMessages: { _, _ in fetched },
            listMessagePage: { _, _, _ in MessageHistoryPage(messages: fetched, olderCursor: nil, hasOlder: false) },
            store: InMemoryMessageStore(),
            eventStream: eventStream,
            recoveryDelay: { _ in },
            sessionCache: FixedSessionCache(messages: cached)
        )
        let collector = HydrationCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/Users/example/project")) }

        let hydrated = await waitUntil {
            collector.latest()?.first?.toolImages.first?.dataURL == ToolImageMappingTests.pngDataURL
        }
        let confirmed = await waitUntil { collector.sawCacheSyncConfirmed }
        task.cancel()

        #expect(hydrated)
        #expect(confirmed)
        let image = collector.latest()?.first?.toolImages.first
        // The thumbnail from the cache stays next to the full picture.
        #expect(image?.previewData == Self.thumbnail)
        #expect(collector.latest()?.first?.parts.count == cached[0].parts.count)
    }

    // MARK: - Store merge

    @Test func authoritativeMergeNeverDowngradesAFullImageToThumbnailOnly() {
        let store = InMemoryMessageStore()
        store.replaceAll(sessionID: "ses-1", messages: [readTurn(images: [fullImage()])])
        let applied = store.mergeAuthoritativeIfRevision(
            sessionID: "ses-1",
            messages: [readTurn(images: [thumbnailImage()])],
            expectedRevision: store.revision(sessionID: "ses-1")
        )

        let image = store.messages(sessionID: "ses-1").first?.toolImages.first
        #expect(applied)
        #expect(image?.dataURL == ToolImageMappingTests.pngDataURL)
        #expect(image?.previewData == Self.thumbnail)
    }

    @Test func authoritativeMergeUpgradesThumbnailOnlyToFull() {
        let store = InMemoryMessageStore()
        store.replaceAll(sessionID: "ses-1", messages: [readTurn(images: [thumbnailImage()])])
        _ = store.mergeAuthoritativeIfRevision(
            sessionID: "ses-1",
            messages: [readTurn(images: [fullImage()])],
            expectedRevision: store.revision(sessionID: "ses-1")
        )

        let image = store.messages(sessionID: "ses-1").first?.toolImages.first
        #expect(image?.dataURL == ToolImageMappingTests.pngDataURL)
        #expect(image?.previewData == Self.thumbnail)
    }

    @Test func hydrateMediaFillsOnlyMediaAndLeavesTheRevision() {
        let store = InMemoryMessageStore()
        store.replaceAll(sessionID: "ses-1", messages: [readTurn(images: [thumbnailImage()], output: "cached output")])
        let revision = store.revision(sessionID: "ses-1")

        let changed = store.hydrateMedia(sessionID: "ses-1", from: [readTurn(images: [fullImage()], output: "server output")])
        let again = store.hydrateMedia(sessionID: "ses-1", from: [readTurn(images: [fullImage()], output: "server output")])

        let message = store.messages(sessionID: "ses-1").first
        #expect(changed)
        #expect(!again)
        #expect(store.revision(sessionID: "ses-1") == revision)
        #expect(message?.toolImages.first?.dataURL == ToolImageMappingTests.pngDataURL)
        guard case .tool(let tool)? = message?.parts.first else {
            Issue.record("Expected the tool part")
            return
        }
        #expect(tool.output == "cached output")
    }

    @Test func mergeImagesKeepsPriorWhenNextIsEmpty() {
        let prior = [fullImage()]
        #expect(MessageMedia.mergeImages(prior: prior, next: []) == prior)
    }

    // MARK: - Fixtures

    private func readTurn(images: [FilePart], output: String = "Image read successfully") -> Message {
        Message(
            id: "msg_asst",
            role: .assistant,
            text: "",
            parts: [
                .tool(ToolPart(
                    id: "call_read",
                    toolName: "read",
                    status: .completed,
                    input: ["filePath": .string("/Users/example/project/shot.png")],
                    output: output,
                    timeStartEpochMs: 1,
                    timeEndEpochMs: 2,
                    images: images
                )),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1,
            completedAtEpochMs: 2
        )
    }

    private func fullImage() -> FilePart {
        FilePart(
            id: "call_read:file:0",
            filename: "shot.png",
            mime: "image/png",
            kind: .image,
            dataURL: ToolImageMappingTests.pngDataURL
        )
    }

    private func thumbnailImage() -> FilePart {
        FilePart(
            id: "call_read:file:0",
            filename: "shot.png",
            mime: "image/png",
            kind: .image,
            dataURL: "",
            previewData: Self.thumbnail
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

private final class HydrationCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var emissions: [[Message]] = []
    private var confirmed = false

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            lock.lock()
            switch update {
            case .messages(let messages, _):
                emissions.append(messages)
            case .cacheSyncConfirmed:
                confirmed = true
            default:
                break
            }
            lock.unlock()
        }
    }

    func latest() -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        return emissions.last
    }

    var sawCacheSyncConfirmed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return confirmed
    }
}

private final class FixedSessionCache: SessionCache, @unchecked Sendable {
    private let messages: [Message]

    init(messages: [Message]) {
        self.messages = messages
    }

    func seedSessions(worktree: String) async -> [Session]? { nil }
    func rememberSessions(worktree: String, sessions: [Session]) async {}
    func openMessages(sessionID: String, worktree: String) -> any SessionMessageCacheHandle {
        FixedMessageCacheHandle(messages: messages)
    }
    func purgeWorktree(serverBaseURL: String, worktree: String) async {}
    func clearAll() async {}
    func estimatedSizeBytes() async -> Int64 { 0 }
}

private final class FixedMessageCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    private let messages: [Message]

    init(messages: [Message]) {
        self.messages = messages
    }

    func seed() async -> [Message]? { messages }
    func note(messages: [Message]) {}
    func flush() async {}
    func close() {}
}
