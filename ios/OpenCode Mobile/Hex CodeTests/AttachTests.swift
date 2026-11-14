import CoreGraphics
import Foundation
import ImageIO
import OpenCodeKit
import Testing
import UIKit
import UniformTypeIdentifiers
@testable import Hex_Code

/// Test images, made in memory.
nonisolated enum TestImages {
    /// A folder of its own for each test's prepared files.
    static func folder() -> URL {
        FileManager.default.temporaryDirectory.appending(path: "attach-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
    }

    /// `width` × `height`, the left half red and the right half blue, in Display P3.
    static func cgImage(width: Int, height: Int, alpha: Bool = false) -> CGImage {
        let space = CGColorSpace(name: CGColorSpace.displayP3)!
        let info = alpha ? CGImageAlphaInfo.premultipliedLast.rawValue : CGImageAlphaInfo.noneSkipLast.rawValue
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space, bitmapInfo: info)!
        if alpha { context.clear(CGRect(x: 0, y: 0, width: width, height: height)) }
        context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
        context.setFillColor(CGColor(red: 0, green: 0, blue: 1, alpha: alpha ? 0.5 : 1))
        context.fill(CGRect(x: width / 2, y: 0, width: width - width / 2, height: height))
        return context.makeImage()!
    }

    /// Encoded as `type`, with an EXIF orientation and camera and GPS metadata.
    static func encoded(_ image: CGImage, as type: UTType, orientation: Int = 1) -> Data? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, type.identifier as CFString, 1, nil) else { return nil }
        let properties: [CFString: Any] = [
            kCGImagePropertyOrientation: orientation,
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Apple", kCGImagePropertyTIFFModel: "iPhone 16e"],
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 52.37, kCGImagePropertyGPSLatitudeRef: "N"],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifLensModel: "Back Camera"],
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    static func jpeg(width: Int, height: Int, orientation: Int = 1) -> Data {
        encoded(cgImage(width: width, height: height), as: .jpeg, orientation: orientation)!
    }

    /// A small prepared image, for the draft and sending tests.
    static func prepared(in folder: URL = folder()) throws -> PreparedImage {
        try ImagePreparer.prepare(data: jpeg(width: 64, height: 48), in: folder)
    }

    /// What ImageIO reads back from a prepared file.
    static func properties(_ file: URL) -> (type: String?, properties: [CFString: Any]) {
        let source = CGImageSourceCreateWithURL(file as CFURL, nil)!
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        return (CGImageSourceGetType(source) as String?, properties)
    }

    /// The colour at (x, y), top left origin, as 0–255 RGB.
    static func pixel(_ file: URL, x: Int, y: Int) -> (r: Int, g: Int, b: Int) {
        let source = CGImageSourceCreateWithURL(file as CFURL, nil)!
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
        var data = [UInt8](repeating: 0, count: 4)
        let context = CGContext(
            data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )!
        context.draw(image, in: CGRect(x: -x, y: -(image.height - 1 - y), width: image.width, height: image.height))
        return (Int(data[0]), Int(data[1]), Int(data[2]))
    }
}

/// Preparing a picked image (app plan, "Attach" › Preparing). One at a time: the large images
/// would otherwise take every core and starve other suites' timers.
@Suite(.serialized)
struct ImagePreparerTests {
    @Test func aLargePhotoIsScaledToTwoThousandPixelsAsJPEG() throws {
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 4000, height: 3000), in: TestImages.folder())
        #expect(prepared.pixelWidth == 2000)
        #expect(prepared.pixelHeight == 1500)
        let read = TestImages.properties(prepared.file)
        #expect(read.type == UTType.jpeg.identifier)
        #expect(read.properties[kCGImagePropertyPixelWidth] as? Int == 2000)
        let bytes = try Data(contentsOf: prepared.file)
        #expect(prepared.byteCount == bytes.count)
        #expect(prepared.byteCount < ImagePreparer.maxBytes)
    }

    @Test func aSmallImageIsNeverScaledUp() throws {
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 300, height: 120), in: TestImages.folder())
        #expect(prepared.pixelWidth == 300)
        #expect(prepared.pixelHeight == 120)
    }

    @Test func noMetadataIsKept() throws {
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 400, height: 300, orientation: 3), in: TestImages.folder())
        let properties = TestImages.properties(prepared.file).properties
        #expect(properties[kCGImagePropertyGPSDictionary] == nil)
        let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        #expect(tiff?[kCGImagePropertyTIFFMake] == nil)
        #expect(tiff?[kCGImagePropertyTIFFModel] == nil)
        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any]
        #expect(exif?[kCGImagePropertyExifLensModel] == nil)
        // Rotated already, so nothing turns it again.
        #expect((properties[kCGImagePropertyOrientation] as? Int ?? 1) == 1)
    }

    @Test func itIsRotatedUpright() throws {
        // Stored 300 × 100, red on the left; orientation 6 shows it turned 90° clockwise, red on top.
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 300, height: 100, orientation: 6), in: TestImages.folder())
        #expect(prepared.pixelWidth == 100)
        #expect(prepared.pixelHeight == 300)
        let top = TestImages.pixel(prepared.file, x: 50, y: 20)
        let bottom = TestImages.pixel(prepared.file, x: 50, y: 280)
        #expect(top.r > 200 && top.b < 60)
        #expect(bottom.b > 200 && bottom.r < 60)
    }

    @Test func itIsSRGB() throws {
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 200, height: 100), in: TestImages.folder())
        let source = CGImageSourceCreateWithURL(prepared.file as CFURL, nil)!
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
        #expect(image.colorSpace?.name == CGColorSpace.sRGB)
        #expect(image.bitsPerComponent == 8)
    }

    @Test func heicBecomesJPEG() throws {
        let heic = try #require(
            TestImages.encoded(TestImages.cgImage(width: 2400, height: 1200), as: .heic),
            "This simulator can't write HEIC"
        )
        let prepared = try ImagePreparer.prepare(data: heic, in: TestImages.folder())
        #expect(TestImages.properties(prepared.file).type == UTType.jpeg.identifier)
        #expect(prepared.pixelWidth == 2000)
        #expect(prepared.pixelHeight == 1000)
    }

    @Test func aTransparentScreenshotBecomesAnOpaqueJPEG() throws {
        let png = try #require(TestImages.encoded(TestImages.cgImage(width: 1170, height: 2532, alpha: true), as: .png))
        let prepared = try ImagePreparer.prepare(data: png, in: TestImages.folder())
        #expect(TestImages.properties(prepared.file).type == UTType.jpeg.identifier)
        #expect(prepared.pixelWidth == 924)
        #expect(prepared.pixelHeight == 2000)
        // Transparent shows on white, not black.
        let clear = TestImages.pixel(prepared.file, x: 900, y: 1000)
        #expect(clear.g > 100)
    }

    @Test func aCameraPhotoIsDrawnUpright() throws {
        let sideways = UIImage(cgImage: TestImages.cgImage(width: 2400, height: 1800), scale: 1, orientation: .right)
        let prepared = try ImagePreparer.prepare(image: sideways, in: TestImages.folder())
        #expect(prepared.pixelWidth == 1500)
        #expect(prepared.pixelHeight == 2000)
        #expect(TestImages.properties(prepared.file).type == UTType.jpeg.identifier)
    }

    @Test func somethingThatIsNotAnImageIsRefused() {
        #expect(throws: ImagePreparer.Failure.unreadable) {
            try ImagePreparer.prepare(data: Data("not an image".utf8), in: TestImages.folder())
        }
    }

    @Test func theThumbnailIsSmall() throws {
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 3000, height: 1000), in: TestImages.folder())
        let thumbnail = try #require(prepared.thumbnail.cgImage)
        #expect(max(thumbnail.width, thumbnail.height) == ImagePreparer.thumbnailPixelSize)
    }

    @Test func theFileGoesWithTheLastReference() throws {
        var prepared: PreparedImage? = try TestImages.prepared()
        let file = try #require(prepared?.file)
        #expect(FileManager.default.fileExists(atPath: file.path))
        prepared = nil
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func theDataURLHasNoLineBreaks() throws {
        let prepared = try ImagePreparer.prepare(data: TestImages.jpeg(width: 1500, height: 1000), in: TestImages.folder())
        let url = try prepared.dataURL()
        #expect(url.hasPrefix("data:image/jpeg;base64,"))
        #expect(!url.contains("\n") && !url.contains("\r"))
        let base64 = String(url.dropFirst("data:image/jpeg;base64,".count))
        let bytes = try Data(contentsOf: prepared.file)
        #expect(Data(base64Encoded: base64) == bytes)
    }
}

/// Attaching on home: the limit, preparing, removing, a failed image (app plan, "Attach").
@MainActor
struct AttachDraftTests {
    private let gate = Gate()

    private func make(_ sender: FakeSender? = nil, prepare: (@Sendable (PickedImage.Content) async throws -> PreparedImage)? = nil) -> HomeModel {
        let folder = TestImages.folder()
        let home = HomeModel(
            recorder: FakeRecorder(),
            speech: FakeSpeech(),
            sleep: { _ in },
            prepareImage: prepare ?? { content in
                guard case let .data(data) = content else { throw PickedImage.Unreadable() }
                return try ImagePreparer.prepare(data: data, in: folder)
            }
        )
        if let sender { home.sender = sender }
        return home
    }

    private func picked(_ kind: DraftImage.Kind = .photo, wait gate: Gate? = nil) -> PickedImage {
        PickedImage(kind: kind) {
            await gate?.wait()
            return .data(TestImages.jpeg(width: 64, height: 48))
        }
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func pickedImagesShowAtOnceAndSendWaitsUntilTheyArePrepared() async {
        let home = make()
        home.attach([picked(wait: gate), picked(.screenshot, wait: gate)])
        #expect(home.draft.images.count == 2)
        #expect(home.draft.images.allSatisfy { $0.isPreparing })
        #expect(home.imagesPending)
        #expect(!home.canSend)
        gate.open()
        await eventually { !home.imagesPending }
        #expect(home.draft.images.allSatisfy { !$0.isPreparing })
        // Images alone make a message.
        #expect(home.canSend)
    }

    @Test func fourAttachmentsIsTheMost() async {
        let home = make()
        home.attach((0 ..< 5).map { _ in picked() })
        #expect(home.draft.images.count == 4)
        #expect(home.toast?.title == "4 attachments is the most per message")
        #expect(home.draft.remainingImages == 0)
        home.toasts.dismiss()
        #expect(home.attachTapped() == false)
        #expect(home.toast?.title == "4 attachments is the most per message")
        await eventually { !home.imagesPending }
        home.removeImage(home.draft.images[0].id)
        #expect(home.attachTapped())
        #expect(home.draft.remainingImages == 1)
    }

    @Test func anImageThatCantBeReadIsLeftOutWithANotice() async {
        let home = make(prepare: { _ in throw ImagePreparer.Failure.unreadable })
        home.attach([picked()])
        await eventually { home.draft.images.isEmpty }
        #expect(home.toast?.title == "Couldn’t add this image")
        #expect(home.draft.isEmpty)
    }

    @Test func removingAPreparingImageDropsIt() async {
        let home = make()
        home.attach([picked(wait: gate)])
        home.removeImage(home.draft.images[0].id)
        #expect(home.draft.images.isEmpty)
        gate.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(home.draft.images.isEmpty)
        #expect(home.toast == nil)
    }

    @Test func clearingTheDraftClearsItsImages() async {
        let home = make()
        home.attach([picked()])
        await eventually { !home.imagesPending }
        home.clearDraft()
        #expect(home.draft.images.isEmpty)
        #expect(home.toast?.title == "Draft cleared")
    }
}

/// Sending images (app plan, "Attach" › Sending, Model can't see images, Undo).
@MainActor
struct AttachSendingTests {
    private let folder = TestImages.folder()

    private func make(_ sender: FakeSender) -> HomeModel {
        let folder = folder
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in }) { content in
            guard case let .data(data) = content else { throw PickedImage.Unreadable() }
            return try ImagePreparer.prepare(data: data, in: folder)
        }
        home.sender = sender
        return home
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    /// "hello there", then a photo, a screenshot and a photo.
    private func draft(_ home: HomeModel) async {
        await home.toggleHandsFreeTake()
        await home.toggleHandsFreeTake()
        home.attach([.photo, .screenshot, .photo].map { kind in
            PickedImage(kind: kind) { .data(TestImages.jpeg(width: 64, height: 48)) }
        })
        await eventually { home.canSend }
    }

    @Test func imagesGoInTheSameMessageNamedByKind() async {
        let sender = FakeSender()
        let home = make(sender)
        await draft(home)
        let files = home.draft.images.map(\.prepared)
        home.send()
        #expect(home.draft.isEmpty)
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["hello there"])
        #expect(sender.sentImages.first?.map(\.name) == ["Photo 1.jpg", "Screenshot 1.jpg", "Photo 2.jpg"])
        let sentFiles = sender.sentImages.first?.map(\.image) ?? []
        #expect(sentFiles.count == 3 && zip(sentFiles, files).allSatisfy { $0 === $1 })
    }

    @Test func nowCarriesTheImagesToo() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let home = make(sender)
        await draft(home)
        home.sendMode = .now
        home.send()
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["[Correction] hello there"])
        #expect(sender.sentImages.first?.count == 3)
    }

    @Test func aModelThatCantSeeImagesGetsTheTextOnly() async {
        let sender = FakeSender()
        let home = make(sender)
        home.imageInput = ImageInput(canSee: false, modelName: "Beam")
        await draft(home)
        home.send()
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["hello there"])
        #expect(sender.sentImages == [[]])
        await eventually { home.toast?.title == "Sent without the images" }
        #expect(home.toast?.detail == "Beam can’t see them")
        #expect(home.draft.isEmpty)
    }

    @Test func imagesAloneDontSendToAModelThatCantSeeThem() async {
        let sender = FakeSender()
        let home = make(sender)
        home.imageInput = ImageInput(canSee: false, modelName: "Beam")
        home.attach([PickedImage(kind: .photo) { .data(TestImages.jpeg(width: 64, height: 48)) }])
        await eventually { !home.imagesPending }
        #expect(!home.canSend)
        home.imageInput = .unknown
        #expect(home.canSend)
    }

    @Test func aFailedSendGivesTheImagesBack() async {
        let sender = FakeSender()
        sender.fails = true
        let home = make(sender)
        await draft(home)
        let saved = home.draft
        home.send()
        await eventually { home.toast?.title == "Couldn’t send" }
        #expect(home.draft == saved)
        #expect(home.draft.images.count == 3)
        // The prepared files are still there to send again.
        #expect(home.draft.images.allSatisfy { FileManager.default.fileExists(atPath: $0.prepared!.file.path) })
    }

    @Test func undoOfAQueuedMessageBringsTheImagesBack() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let home = make(sender)
        await draft(home)
        let saved = home.draft
        home.send()
        await eventually { home.toast?.title == "Queued · sends after this turn" }
        #expect(home.draft.isEmpty)
        home.toasts.performAction()
        await eventually { home.toast?.title == "Taken back" }
        #expect(sender.cancelled == ["msg_1"])
        #expect(home.draft == saved)
        #expect(home.draft.images.allSatisfy { FileManager.default.fileExists(atPath: $0.prepared!.file.path) })
    }

    @Test func imagesAloneSendAnEmptyText() async {
        let sender = FakeSender()
        let home = make(sender)
        home.attach([PickedImage(kind: .screenshot) { .data(TestImages.jpeg(width: 64, height: 48)) }])
        await eventually { home.canSend }
        home.send()
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == [""])
        #expect(sender.sentImages.first?.map(\.name) == ["Screenshot 1.jpg"])
        await eventually { home.toast?.title == "Sent · starting now" }
        #expect(home.toast?.detail == "1 image")
    }

    @Test func imageInputComesFromTheModelList() {
        let choice = ModelChoice(providerID: "opencode", modelID: "beam-free", variant: nil)
        let options = [
            ModelSelectionOption(providerID: "opencode", providerName: "OpenCode", modelID: "beam-free", modelName: "Beam", variants: [], supportsImageInput: false),
            ModelSelectionOption(providerID: "opencode", providerName: "OpenCode", modelID: "glm", modelName: "GLM", variants: []),
        ]
        #expect(ImageInput.of(choice, in: options, savedName: nil) == ImageInput(canSee: false, modelName: "Beam"))
        #expect(ImageInput.of(choice, in: options, savedName: "Beam Free").modelName == "Beam Free")
        let unknown = ModelChoice(providerID: "x", modelID: "y", variant: nil)
        #expect(ImageInput.of(unknown, in: options, savedName: nil).canSee)
    }
}

/// The request body and the inbox, through OpenCodeKit (app plan, "Attach" › Sending, Waiting to send).
@MainActor
@Suite(.serialized)
struct AttachRequestTests {
    @Test func theImagesTravelAsDataURLsInThePrompt() async throws {
        PromptURLProtocol.reset(#"{"data":{"id":"msg_9","sessionID":"ses_a","type":"user","payload":{"text":"Look","files":[{"data":"AAAA","mime":"image/jpeg","name":"Photo 1.jpg"}]},"delivery":"queue"}}"#)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PromptURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        let service = OpenCodeSessionService(connection: connection, urlSession: URLSession(configuration: configuration))
        let photo = try ImagePreparer.prepare(data: TestImages.jpeg(width: 2600, height: 1800), in: TestImages.folder())
        let shot = try TestImages.prepared()

        let id = try await service.prompt(
            sessionID: "ses_a",
            text: "Look",
            images: [OutgoingImage(name: "Photo 1.jpg", image: photo), OutgoingImage(name: "Screenshot 1.jpg", image: shot)],
            delivery: .queue
        )

        #expect(id == "msg_9")
        let request = try #require(PromptURLProtocol.lastRequest)
        #expect(request.url?.path == "/api/session/ses_a/prompt")
        let body = try #require(PromptURLProtocol.lastBody)
        let raw = try #require(String(data: body, encoding: .utf8))
        #expect(!raw.contains("\\n") && !raw.contains("\n"))
        let object = try JSONSerialization.jsonObject(with: body)
        let json = try #require(object as? [String: Any])
        #expect(json["text"] as? String == "Look")
        #expect(json["delivery"] as? String == "queue")
        let files = try #require(json["files"] as? [[String: Any]])
        #expect(files.map { $0["name"] as? String } == ["Photo 1.jpg", "Screenshot 1.jpg"])
        #expect(files.allSatisfy { Set($0.keys) == ["uri", "name"] })
        let uri = try #require(files.first?["uri"] as? String)
        #expect(uri.hasPrefix("data:image/jpeg;base64,"))
        let bytes = Data(base64Encoded: String(uri.dropFirst("data:image/jpeg;base64,".count)))
        let stored = try Data(contentsOf: photo.file)
        #expect(bytes == stored)
    }

    @Test func textAloneSendsNoFilesField() async throws {
        PromptURLProtocol.reset(#"{"data":{"id":"msg_1","sessionID":"ses_a","type":"user","payload":{"text":"hi"},"delivery":"steer"}}"#)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PromptURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        let service = OpenCodeSessionService(connection: connection, urlSession: URLSession(configuration: configuration))
        _ = try await service.prompt(sessionID: "ses_a", text: "hi", images: [], delivery: .steer)
        let object = try JSONSerialization.jsonObject(with: PromptURLProtocol.lastBody ?? Data())
        let json = try #require(object as? [String: Any])
        #expect(json["files"] == nil)
        #expect(json["delivery"] as? String == "steer")
    }

    /// A long base64 string, as the server sends back for a photo.
    private static let base64 = String(repeating: "QUJD", count: 200_000)

    @Test func theInboxKeepsNamesAndTypesButNotTheBytes() throws {
        let json = #"{"data":[{"id":"msg_1","sessionID":"ses_a","time":{"created":1},"type":"user","payload":{"text":"Look","files":[{"data":"\#(Self.base64)","mime":"image/jpeg","source":{"type":"inline"},"name":"Photo 1.jpg"},{"data":"iVBO","mime":"image/png","name":"Screenshot 1.jpg"}]},"delivery":"queue"}]}"#
        let inbox = try JSONDecoder().decode(APIListEnvelope<InboxItemV2>.self, from: Data(json.utf8))
        #expect(inbox.data == [InboxItemV2(
            id: "msg_1",
            sessionID: "ses_a",
            delivery: "queue",
            text: "Look",
            files: [InboxFileV2(name: "Photo 1.jpg", mime: "image/jpeg"), InboxFileV2(name: "Screenshot 1.jpg", mime: "image/png")],
            type: "user"
        )])
        #expect(String(describing: inbox.data).count < 1000)
    }

    @Test func theEnqueuedEventKeepsNamesAndTypesButNotTheBytes() {
        let line = EventLines.line(
            "session.inbox.enqueued",
            #"{"inboxID":"msg_2","sessionID":"ses_a","item":{"type":"user","payload":{"text":"","files":[{"data":"\#(Self.base64)","mime":"image/jpeg","source":{"type":"inline"},"name":"Photo 1.jpg"},{"data":"x","mime":"image/jpeg","name":"Photo 2.jpg"}]},"delivery":"queue"}}"#
        )
        let event = SessionEvent.decode(line)
        let files = [InboxFileV2(name: "Photo 1.jpg", mime: "image/jpeg"), InboxFileV2(name: "Photo 2.jpg", mime: "image/jpeg")]
        #expect(event == .inboxEnqueued(sessionID: "ses_a", id: "msg_2", delivery: "queue", text: "", files: files))
        var state = LiveSessionState(rootID: "ses_a", hasRun: true)
        _ = state.apply(event!)
        #expect(state.inbox.first?.imageCount == 2)
        #expect(String(describing: state.inbox).count < 1000)
    }
}

/// The Waiting to send sheet's thumbnails, kept by inbox id (app plan, "Attach" › Waiting to send).
@MainActor
struct WaitingImagesTests {
    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    private func make(_ server: FakeSessionServer) -> (TodaySession, FakeTransport) {
        let transport = FakeTransport()
        let session = TodaySession(
            setup: DailySessionSetup(service: server, memory: FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: HexToaster(),
            api: server,
            stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
            now: { Date(timeIntervalSince1970: 1_793_880_000) },
            timeZone: TimeZone(identifier: "UTC")!
        )
        return (session, transport)
    }

    @Test func thePhonesThumbnailsStayUntilDelivered() async throws {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        let image = try TestImages.prepared()
        let id = try await session.send("Look", images: [OutgoingImage(name: "Photo 1.jpg", image: image)], delivery: .queue)
        #expect(server.promptImages.first?.map(\.name) == ["Photo 1.jpg"])
        #expect(session.waitingThumbnails[id]?.count == 1)
        transport.send(EventLines.enqueued("ses_new1", id: id))
        await eventually { session.waitingMessages.count == 1 }
        transport.send(EventLines.delivered("ses_new1", id: id))
        await eventually { session.waitingMessages.isEmpty }
        #expect(session.waitingThumbnails[id] == nil)
    }

    @Test func cancellingDropsTheThumbnails() async throws {
        let server = FakeSessionServer()
        let (session, _) = make(server)
        await session.open()
        let id = try await session.send("", images: [OutgoingImage(name: "Photo 1.jpg", image: try TestImages.prepared())], delivery: .queue)
        try await session.cancelWaiting(id)
        #expect(session.waitingThumbnails[id] == nil)
    }
}

/// Records the prompt request and answers with a fixed body.
nonisolated final class PromptURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastBody: Data?
    nonisolated(unsafe) static var reply = ""

    static func reset(_ reply: String) {
        lastRequest = nil
        lastBody = nil
        self.reply = reply
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        Self.lastBody = request.httpBody ?? request.httpBodyStream.map(Self.read)
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(Self.reply.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
