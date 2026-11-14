import Foundation
import Testing
@testable import OpenCode_Mobile

/// The viewer's full picture: from memory when the store still has it, else from the server's
/// copy of the one message, hydrated back into the store.
@MainActor
struct MessageImageLoaderTests {
    nonisolated private static let imageID = "call_read:file:0"
    nonisolated private static let thumbnail = Data([0xFF, 0xD8, 0xFF, 0xD9])
    /// A 1×1 PNG.
    nonisolated private static let png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    @Test func memoryHitDoesNotFetch() async throws {
        let fetches = Counter()
        let loader = MessageImageLoader(
            cachedMessages: { _ in [Self.turn(dataURL: Self.png)] },
            fetchMessage: { _ in
                fetches.increment()
                throw MessageImageError.notFound
            },
            hydrate: { _ in }
        )

        let image = try await loader.fullImage(messageID: "msg_asst", imageID: Self.imageID)

        #expect(image.dataURL == Self.png)
        #expect(fetches.value == 0)
    }

    @Test func thumbnailOnlyFetchesTheMessageAndHydratesTheStore() async throws {
        let store = InMemoryMessageStore()
        store.replaceAll(sessionID: "slot", messages: [Self.turn(dataURL: "", previewData: Self.thumbnail)])
        let fetched = Counter()
        let loader = MessageImageLoader(
            cachedMessages: { id in id.flatMap { store.message(sessionID: "slot", messageID: $0) }.map { [$0] } ?? [] },
            fetchMessage: { messageID in
                fetched.increment()
                #expect(messageID == "msg_asst")
                return Self.turn(dataURL: Self.png)
            },
            hydrate: { message in _ = store.hydrateMedia(sessionID: "slot", from: [message]) }
        )

        let image = try await loader.fullImage(messageID: "msg_asst", imageID: Self.imageID)

        #expect(fetched.value == 1)
        #expect(image.dataURL == Self.png)
        #expect(image.previewData == Self.thumbnail)
        let stored = store.messages(sessionID: "slot").first?.toolImages.first
        #expect(stored?.dataURL == Self.png)
        #expect(stored?.previewData == Self.thumbnail)
    }

    @Test func unknownMessageIsFoundByImageID() async throws {
        let loader = MessageImageLoader(
            cachedMessages: { id in id == nil ? [Self.turn(dataURL: "", previewData: Self.thumbnail)] : [] },
            fetchMessage: { messageID in
                #expect(messageID == "msg_asst")
                return Self.turn(dataURL: Self.png)
            },
            hydrate: { _ in }
        )

        let image = try await loader.fullImage(messageID: nil, imageID: Self.imageID)
        #expect(image.dataURL == Self.png)
    }

    @Test func serverCopyWithoutThePictureFails() async {
        let loader = MessageImageLoader(
            cachedMessages: { _ in [Self.turn(dataURL: "", previewData: Self.thumbnail)] },
            fetchMessage: { _ in Self.turn(dataURL: "") },
            hydrate: { _ in Issue.record("Nothing to hydrate") }
        )

        await #expect(throws: MessageImageError.unavailable) {
            _ = try await loader.fullImage(messageID: "msg_asst", imageID: Self.imageID)
        }
    }

    @Test func fetchFailurePropagates() async {
        let loader = MessageImageLoader(
            cachedMessages: { _ in [] },
            fetchMessage: { _ in throw MessageRemoteError.messageNotFound },
            hydrate: { _ in }
        )

        await #expect(throws: MessageRemoteError.messageNotFound) {
            _ = try await loader.fullImage(messageID: "msg_asst", imageID: Self.imageID)
        }
    }

    @Test func noMessageToAskForFails() async {
        let loader = MessageImageLoader(cachedMessages: { _ in [] }, fetchMessage: { _ in Self.turn(dataURL: "") }, hydrate: { _ in })
        await #expect(throws: MessageImageError.notFound) {
            _ = try await loader.fullImage(messageID: nil, imageID: Self.imageID)
        }
    }

    nonisolated static func turn(dataURL: String, previewData: Data? = nil) -> Message {
        Message(
            id: "msg_asst",
            role: .assistant,
            text: "",
            parts: [
                .tool(ToolPart(
                    id: "call_read",
                    toolName: "read",
                    status: .completed,
                    output: "Image read successfully",
                    images: [
                        FilePart(
                            id: imageID,
                            filename: "shot.png",
                            mime: "image/png",
                            kind: .image,
                            dataURL: dataURL,
                            previewData: previewData
                        ),
                    ]
                )),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1,
            completedAtEpochMs: 2
        )
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}
