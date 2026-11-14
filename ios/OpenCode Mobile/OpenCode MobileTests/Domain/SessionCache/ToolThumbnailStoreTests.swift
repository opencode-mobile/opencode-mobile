import Foundation
import Testing
import UIKit
@testable import OpenCode_Mobile

/// Tool images in the session cache: metadata in the JSON, a small JPEG beside it, never the
/// full picture; disk use bounded per session and overall.
@Suite(.serialized)
@MainActor
struct ToolThumbnailStoreTests {
    // MARK: - Codec

    @Test func readResultSurvivesTheCacheAsThumbnailMetadataWithoutBase64() throws {
        let image = Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)
        let raw = try SessionCacheCodec.encodeMessages([Self.readTurn(images: [image])], thumbnailImageIDs: [image.id])

        #expect(!raw.contains("base64"))
        #expect(!raw.contains(ToolImageMappingTests.pngBase64))
        let timeline = try #require(SessionCacheCodec.decodeMessageTimeline(raw))
        let decoded = try #require(timeline.messages.first?.toolImages.first)
        #expect(decoded.id == image.id)
        #expect(decoded.filename == "shot-0.png")
        #expect(decoded.mime == "image/png")
        #expect(decoded.kind == .image)
        #expect(decoded.dataURL.isEmpty)
        #expect(decoded.previewData == nil)
        #expect(timeline.thumbnailImageIDs == [image.id])
    }

    @Test func imageWithoutAThumbnailFileIsNotFlagged() throws {
        let image = Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)
        let raw = try SessionCacheCodec.encodeMessages([Self.readTurn(images: [image])])
        let timeline = try #require(SessionCacheCodec.decodeMessageTimeline(raw))
        #expect(timeline.messages.first?.toolImages.map(\.id) == [image.id])
        #expect(timeline.thumbnailImageIDs.isEmpty)
    }

    @Test func sessionCacheWritesTheThumbnailFileAndSeedsItBack() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let thumbnails = ToolThumbnailStore(root: root)
        let store = InMemorySessionCacheStore()
        let cache = DefaultSessionCache(store: store, connectionIdProvider: { "profile-1" }, thumbnails: thumbnails)
        let dataURL = Self.pngDataURL(width: 1200, height: 800)
        let image = Self.image(index: 0, dataURL: dataURL)

        let writer = cache.openMessages(sessionID: "ses_1", worktree: "/Users/example/project")
        writer.note(messages: [Self.readTurn(images: [image])])
        await writer.flush()

        let file = thumbnails.fileURL(connectionID: "profile-1", sessionID: "ses_1", imageID: image.id)
        let bytes = try Data(contentsOf: file)
        #expect(bytes.count <= ToolThumbnail.maxBytes)
        #expect(!file.path.contains("profile-1"))
        let record = await store.load(key: SessionCacheKey(
            connectionId: "profile-1",
            worktree: "/Users/example/project",
            kind: .messageTimeline,
            entryId: "ses_1"
        ))
        #expect(record?.value.payloadJSON.contains("base64") == false)

        let reader = cache.openMessages(sessionID: "ses_1", worktree: "/Users/example/project")
        let seeded = try #require(await reader.seed()?.first?.toolImages.first)
        #expect(seeded.previewData == bytes)
        #expect(seeded.dataURL.isEmpty)
    }

    @Test func missingThumbnailFileSeedsWithoutAThumbnail() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let thumbnails = ToolThumbnailStore(root: root, makeThumbnail: { _ in Data(repeating: 1, count: 100) })
        let cache = DefaultSessionCache(store: InMemorySessionCacheStore(), connectionIdProvider: { "profile-1" }, thumbnails: thumbnails)
        let image = Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)
        let writer = cache.openMessages(sessionID: "ses_1", worktree: "/repo")
        writer.note(messages: [Self.readTurn(images: [image])])
        await writer.flush()
        try FileManager.default.removeItem(at: thumbnails.fileURL(connectionID: "profile-1", sessionID: "ses_1", imageID: image.id))

        let seeded = try #require(await cache.openMessages(sessionID: "ses_1", worktree: "/repo").seed()?.first?.toolImages.first)
        #expect(seeded.id == image.id)
        #expect(seeded.previewData == nil)
    }

    // MARK: - Generator

    @Test func thumbnailIsDownscaledToTheLongEdgeAndCapped() throws {
        let data = try #require(Self.pngData(width: 1600, height: 900, noisy: false))
        let thumbnail = try #require(ToolThumbnail.make(data: data))
        let decoded = try #require(UIImage(data: thumbnail))
        #expect(thumbnail.count <= ToolThumbnail.maxBytes)
        #expect(max(decoded.size.width, decoded.size.height) == 400)
    }

    @Test func oversizedThumbnailFallsBackToTheSmallerEdgeOrIsSkipped() throws {
        let data = try #require(Self.pngData(width: 900, height: 900, noisy: true))
        let large = try #require(ToolThumbnail.render(data: data, maxDimension: 400))
        let small = try #require(ToolThumbnail.render(data: data, maxDimension: 300))
        #expect(small.count < large.count)

        let fallback = try #require(ToolThumbnail.make(data: data, maxBytes: large.count - 1))
        #expect(UIImage(data: fallback).map { max($0.size.width, $0.size.height) } == 300)
        #expect(fallback.count <= large.count - 1)

        #expect(ToolThumbnail.make(data: data, maxBytes: small.count - 1) == nil)
    }

    // MARK: - Budgets

    @Test func perSessionKeepsOnlyTheNewestImages() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let thumbnails = ToolThumbnailStore(root: root, makeThumbnail: { _ in Data(repeating: 7, count: 100) })
        let images = (0..<30).map { Self.image(index: $0, dataURL: ToolImageMappingTests.pngDataURL) }

        let kept = await thumbnails.persist(connectionID: "profile-1", sessionID: "ses_1", messages: [Self.readTurn(images: images)])

        #expect(kept == Set(images.suffix(24).map(\.id)))
        let directory = thumbnails.sessionDirectory(connectionID: "profile-1", sessionID: "ses_1")
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path).count == 24)
        #expect(!FileManager.default.fileExists(atPath: thumbnails.fileURL(connectionID: "profile-1", sessionID: "ses_1", imageID: images[0].id).path))
    }

    @Test func perSessionByteBudgetDropsOlderImages() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        var limits = ToolThumbnailLimits()
        limits.perSessionBytes = 250
        let thumbnails = ToolThumbnailStore(root: root, limits: limits, makeThumbnail: { _ in Data(repeating: 7, count: 100) })
        let images = (0..<5).map { Self.image(index: $0, dataURL: ToolImageMappingTests.pngDataURL) }

        let kept = await thumbnails.persist(connectionID: "profile-1", sessionID: "ses_1", messages: [Self.readTurn(images: images)])

        #expect(kept == [images[4].id, images[3].id])
    }

    @Test func imagesThatFallOutOfTheBudgetLoseTheirFile() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        var limits = ToolThumbnailLimits()
        limits.perSessionCount = 2
        let thumbnails = ToolThumbnailStore(root: root, limits: limits, makeThumbnail: { _ in Data(repeating: 7, count: 100) })
        let images = (0..<3).map { Self.image(index: $0, dataURL: ToolImageMappingTests.pngDataURL) }

        await thumbnails.persist(connectionID: "c", sessionID: "s", messages: [Self.readTurn(images: Array(images.prefix(2)))])
        let kept = await thumbnails.persist(connectionID: "c", sessionID: "s", messages: [Self.readTurn(images: images)])

        #expect(kept == [images[1].id, images[2].id])
        #expect(!FileManager.default.fileExists(atPath: thumbnails.fileURL(connectionID: "c", sessionID: "s", imageID: images[0].id).path))
    }

    @Test func globalEvictionDropsLeastRecentlyViewedDownToTheTarget() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        var limits = ToolThumbnailLimits()
        limits.globalBytes = 1_000
        limits.globalTargetBytes = 600
        let thumbnails = ToolThumbnailStore(
            root: root,
            limits: limits,
            evictionDelay: .seconds(60),
            makeThumbnail: { _ in Data(repeating: 7, count: 100) }
        )
        var files: [URL] = []
        for session in 0..<12 {
            let image = Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)
            await thumbnails.persist(connectionID: "c", sessionID: "s\(session)", messages: [Self.readTurn(images: [image])])
            let url = thumbnails.fileURL(connectionID: "c", sessionID: "s\(session)", imageID: image.id)
            // Session 0 was viewed longest ago.
            try FileManager.default.setAttributes(
                [.modificationDate: Date(timeIntervalSince1970: 1_000 + Double(session))],
                ofItemAtPath: url.path
            )
            files.append(url)
        }

        let stats = await thumbnails.evictIfNeeded()

        #expect(stats.bytes == 600)
        #expect(stats.files == 6)
        #expect(stats.evicted == 6)
        #expect(files.prefix(6).allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        #expect(files.suffix(6).allSatisfy { FileManager.default.fileExists(atPath: $0.path) })
    }

    @Test func underTheCapNothingIsEvicted() async {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let thumbnails = ToolThumbnailStore(root: root, makeThumbnail: { _ in Data(repeating: 7, count: 100) })
        await thumbnails.persist(
            connectionID: "c",
            sessionID: "s",
            messages: [Self.readTurn(images: [Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)])]
        )
        let stats = await thumbnails.evictIfNeeded()
        #expect(stats == ToolThumbnailCacheStats(bytes: 100, files: 1, evicted: 0))
    }

    // MARK: - Purges and sweep

    @Test func purgesDeleteTheMatchingThumbnailFolders() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let thumbnails = ToolThumbnailStore(root: root, makeThumbnail: { _ in Data(repeating: 7, count: 100) })
        let store = InMemorySessionCacheStore()
        let profile = ProfileBox("profile-1")
        let cache = DefaultSessionCache(store: store, connectionIdProvider: { profile.value }, thumbnails: thumbnails)
        let image = Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)
        for (session, worktree) in [("ses_a", "/Users/example/a"), ("ses_b", "/Users/example/b")] {
            let handle = cache.openMessages(sessionID: session, worktree: worktree)
            _ = await handle.seed()
            handle.note(messages: [Self.readTurn(images: [image])])
            await handle.flush()
        }
        profile.value = "profile-2"
        let other = cache.openMessages(sessionID: "ses_c", worktree: "/Users/example/a")
        other.note(messages: [Self.readTurn(images: [image])])
        await other.flush()
        func exists(_ connection: String, _ session: String) -> Bool {
            FileManager.default.fileExists(atPath: thumbnails.sessionDirectory(connectionID: connection, sessionID: session).path)
        }
        #expect(exists("profile-1", "ses_a") && exists("profile-1", "ses_b") && exists("profile-2", "ses_c"))

        profile.value = "profile-1"
        await cache.purgeWorktree(serverBaseURL: "https://example.com", worktree: "/Users/example/a")
        #expect(!exists("profile-1", "ses_a"))
        #expect(exists("profile-1", "ses_b"))
        #expect(exists("profile-2", "ses_c"))

        await cache.purgeConnection(profileID: "profile-1", serverBaseURL: "https://example.com")
        #expect(!exists("profile-1", "ses_b"))
        #expect(exists("profile-2", "ses_c"))

        await cache.clearAll()
        #expect(!FileManager.default.fileExists(atPath: root.path))
    }

    @Test func launchSweepDeletesSessionFoldersWithNoCachedTimeline() async throws {
        let root = Self.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let thumbnails = ToolThumbnailStore(root: root, makeThumbnail: { _ in Data(repeating: 7, count: 100) })
        let image = Self.image(index: 0, dataURL: ToolImageMappingTests.pngDataURL)
        for session in ["kept", "orphan"] {
            await thumbnails.persist(connectionID: "c", sessionID: session, messages: [Self.readTurn(images: [image])])
        }
        await thumbnails.persist(connectionID: "gone", sessionID: "x", messages: [Self.readTurn(images: [image])])

        let stats = await thumbnails.maintain(keeping: [(connectionID: "c", sessionID: "kept")])

        #expect(FileManager.default.fileExists(atPath: thumbnails.sessionDirectory(connectionID: "c", sessionID: "kept").path))
        #expect(!FileManager.default.fileExists(atPath: thumbnails.sessionDirectory(connectionID: "c", sessionID: "orphan").path))
        #expect(!FileManager.default.fileExists(
            atPath: root.appendingPathComponent(ToolThumbnailStore.connectionComponent("gone")).path
        ))
        #expect(stats == ToolThumbnailCacheStats(bytes: 100, files: 1, evicted: 2))
    }

    @Test func pathComponentsHideTheConnectionAndSanitiseIDs() {
        let component = ToolThumbnailStore.connectionComponent("https://example.com/|opencode")
        #expect(component.count == 32)
        #expect(!component.contains("example"))
        let file = ToolThumbnailStore.fileComponent("call_read:file:0")
        #expect(!file.contains(":"))
        #expect(file != ToolThumbnailStore.fileComponent("call_read_file_0"))
        #expect(ToolThumbnailStore.fileComponent("prt_abc-1") == "prt_abc-1")
    }

    // MARK: - Fixtures

    static func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("tool-thumbs-tests-\(UUID().uuidString)", isDirectory: true)
    }

    static func image(index: Int, dataURL: String) -> FilePart {
        FilePart(id: "call_read:file:\(index)", filename: "shot-\(index).png", mime: "image/png", kind: .image, dataURL: dataURL)
    }

    static func readTurn(images: [FilePart]) -> Message {
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
                    images: images
                )),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1,
            completedAtEpochMs: 2
        )
    }

    static func pngDataURL(width: Int, height: Int) -> String {
        "data:image/png;base64,\(pngData(width: width, height: height, noisy: false)?.base64EncodedString() ?? "")"
    }

    /// A test picture: soft bands, or seeded noise that compresses badly.
    static func pngData(width: Int, height: Int, noisy: Bool) -> Data? {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        var seed: UInt64 = 0x9E37_79B9_7F4A_7C15
        func next() -> CGFloat {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return CGFloat((seed >> 33) & 0xFF) / 255
        }
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            let block = noisy ? 3 : 40
            for y in stride(from: 0, to: height, by: block) {
                for x in stride(from: 0, to: width, by: block) {
                    if noisy {
                        UIColor(red: next(), green: next(), blue: next(), alpha: 1).setFill()
                    } else {
                        UIColor(hue: CGFloat(x + y) / CGFloat(width + height), saturation: 0.5, brightness: 0.9, alpha: 1).setFill()
                    }
                    context.fill(CGRect(x: x, y: y, width: block, height: block))
                }
            }
        }
        return image.pngData()
    }
}

private final class ProfileBox: @unchecked Sendable {
    private let lock = NSLock()
    private var current: String

    init(_ value: String) {
        current = value
    }

    var value: String {
        get {
            lock.lock()
            defer { lock.unlock() }
            return current
        }
        set {
            lock.lock()
            current = newValue
            lock.unlock()
        }
    }
}
