import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// M7 (app plan, "Attach files (M7)" › Testing): naming, the prompt's path lines and metadata, the
// strip rule, the draft's files and their uploads, the limit, sending, recovery and the goal.

// MARK: Naming and the prompt

struct AttachedFilesNamingTests {
    @Test func slashesBackslashesAndControlCharactersBecomeUnderscores() {
        #expect(AttachedFiles.fileName("a/b\\c.txt") == "a_b_c.txt")
        #expect(AttachedFiles.fileName("tab\there\nnew.csv") == "tab_here_new.csv")
        #expect(AttachedFiles.fileName("  report.pdf  ") == "report.pdf")
        #expect(AttachedFiles.fileName("Ünïcode name (1) #?.txt") == "Ünïcode name (1) #?.txt")
    }

    @Test func aBlankNameBecomesFileAndDotsCantLeaveTheFolder() {
        #expect(AttachedFiles.fileName("") == "File")
        #expect(AttachedFiles.fileName("   ") == "File")
        #expect(AttachedFiles.fileName(".") == "File")
        #expect(AttachedFiles.fileName("..") == "File")
        #expect(AttachedFiles.fileName("../x") == ".._x")
    }

    @Test func aLongNameIsCutTo120KeepingItsExtension() {
        let long = String(repeating: "a", count: 200) + ".pdf"
        let name = AttachedFiles.fileName(long)
        #expect(name.count == 120)
        #expect(name.hasSuffix("a.pdf"))
        let noExtension = AttachedFiles.fileName(String(repeating: "b", count: 130))
        #expect(noExtension.count == 120)
    }

    @Test func eachFileGetsItsOwnFolderUnderUploads() {
        let uuid = UUID(uuidString: "6F9619FF-8B86-D011-B42D-00C04FC964FF")!
        #expect(AttachedFiles.serverPath(tmp: "/tmp/opencode", name: "report.pdf", uuid: uuid)
            == "/tmp/opencode/uploads/6f9619ff-8b86-d011-b42d-00c04fc964ff/report.pdf")
        #expect(AttachedFiles.serverPath(tmp: "/tmp/opencode/", name: "a/b.txt", uuid: uuid)
            == "/tmp/opencode/uploads/6f9619ff-8b86-d011-b42d-00c04fc964ff/a_b.txt")
        #expect(AttachedFiles.serverPath(tmp: "C:\\Temp\\opencode", name: "x.zip", uuid: uuid)
            == "C:\\Temp\\opencode\\uploads\\6f9619ff-8b86-d011-b42d-00c04fc964ff\\x.zip")
        // The same name twice: two folders.
        let first = AttachedFiles.serverPath(tmp: "/t", name: "notes.txt")
        let second = AttachedFiles.serverPath(tmp: "/t", name: "notes.txt")
        #expect(first != second)
        #expect(first.hasSuffix("/notes.txt") && second.hasSuffix("/notes.txt"))
    }

    @Test func theTileShowsAtMostFourCapitals() {
        #expect(AttachedFiles.extensionLabel("report.pdf") == "PDF")
        #expect(AttachedFiles.extensionLabel("build.zip") == "ZIP")
        #expect(AttachedFiles.extensionLabel("data.csv") == "CSV")
        #expect(AttachedFiles.extensionLabel("sheet.numbers") == "NUMB")
        #expect(AttachedFiles.extensionLabel("Makefile") == "")
    }

    @Test func thePromptIsTheTextThenOnePathLinePerFile() {
        #expect(AttachedFiles.promptText("Look at these", paths: ["/t/uploads/u1/a.zip", "/t/uploads/u2/b.pdf"])
            == "Look at these\nAttached file: `/t/uploads/u1/a.zip`\nAttached file: `/t/uploads/u2/b.pdf`")
        #expect(AttachedFiles.promptText("", paths: ["/t/u/a.zip"]) == "Attached file: `/t/u/a.zip`")
        #expect(AttachedFiles.promptText("Just text", paths: []) == "Just text")
        #expect(AttachedFiles.promptText("[Correction] use this", paths: ["/t/u/a.csv"])
            == "[Correction] use this\nAttached file: `/t/u/a.csv`")
    }

    @Test func theMetadataNamesTheFilesForOtherClients() throws {
        let files = [
            OutgoingFile(name: "a.zip", mime: "application/zip", path: "/t/u1/a.zip"),
            OutgoingFile(name: "b", mime: "", path: "/t/u2/b"),
        ]
        let metadata = try #require(AttachedFiles.metadata(displayText: "Look", files: files))
        let json = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(metadata)) as? [String: Any])
        #expect(json["displayText"] as? String == "Look")
        #expect((json["comments"] as? [Any])?.isEmpty == true)
        let attachments = try #require(json["attachments"] as? [[String: String]])
        #expect(attachments == [
            ["name": "a.zip", "mime": "application/zip", "path": "/t/u1/a.zip"],
            ["name": "b", "mime": "application/octet-stream", "path": "/t/u2/b"],
        ])
        #expect(AttachedFiles.metadata(displayText: "x", files: []) == nil)
    }

    @Test func showingTheTextStripsTrailingPathLines() {
        let sent = "Look at these\nAttached file: `/t/u1/a.zip`\nAttached file: `/t/u2/b.pdf`"
        #expect(AttachedFiles.strip(sent).text == "Look at these")
        #expect(AttachedFiles.strip(sent).paths == ["/t/u1/a.zip", "/t/u2/b.pdf"])
        #expect(AttachedFiles.strip(sent + "  \n").text == "Look at these")
        // Only path lines: nothing left to show.
        #expect(AttachedFiles.strip("Attached file: `/t/u/a.zip`") == ("", ["/t/u/a.zip"]))
        // A line in the middle stays: only trailing ones are the attachments.
        let middle = "Attached file: `/x` is how it reads\nthen more"
        #expect(AttachedFiles.strip(middle).text == middle)
        #expect(AttachedFiles.strip("Plain").text == "Plain")
        #expect(AttachedFiles.strip("[Correction] fix\nAttached file: `/t/a`").text == "[Correction] fix")
    }

    @Test func namesComeFromTheMetadataOrThePathLines() {
        let text = "Hi\nAttached file: `/t/uploads/u1/report.pdf`"
        #expect(AttachedFiles.names(text: text, attachments: []) == ["report.pdf"])
        #expect(AttachedFiles.names(text: text, attachments: [PromptAttachment(name: "Report.pdf", mime: "application/pdf", path: "/t/x")]) == ["Report.pdf"])
        #expect(AttachedFiles.names(text: "Hi", attachments: []) == [])
    }

    @Test func theCaptionsAndTheWithLine() {
        let pdf = DraftFile(name: "report.pdf", mime: "application/pdf", path: "/t/a")
        let csv = DraftFile(name: "data.csv", mime: "text/csv", path: "/t/b")
        #expect(AttachmentText.caption(images: 0, files: [pdf], uploadPercent: nil) == "report.pdf · goes with your next message")
        #expect(AttachmentText.caption(images: 0, files: [pdf, csv], uploadPercent: nil) == "2 files · go with your next message")
        #expect(AttachmentText.caption(images: 2, files: [pdf], uploadPercent: nil) == "2 images, 1 file · go with your next message")
        #expect(AttachmentText.caption(images: 1, files: [], uploadPercent: nil) == "1 image · goes with your next message")
        #expect(AttachmentText.caption(images: 1, files: [pdf], uploadPercent: 45) == "Uploading · 45%")
        #expect(AttachmentText.with([]) == nil)
        #expect(AttachmentText.with(["report.pdf"]) == "With report.pdf")
        #expect(AttachmentText.with(["a", "b", "c"]) == "With 3 attachments")
        #expect(AttachmentText.count(images: 2, files: 1) == "2 images, 1 file")
        #expect(AttachmentText.count(images: 0, files: 1) == "1 file")
        #expect(AttachmentText.count(images: 0, files: 2) == "2 files")
    }
}

// MARK: The upload

/// Records each write and answers with a fixed result.
final class FakeUploadAPI: AttachmentUploadAPI, @unchecked Sendable {
    var answer = AttachmentUploadHTTPResult(statusCode: 200, body: Data(#"{"location":{"directory":"/p"},"data":{"path":"/srv/tmp/uploads/u/report.pdf"}}"#.utf8))
    private(set) var writes: [(path: String, directory: String?)] = []

    func write(fileURL: URL, path: String, directory: String?, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> AttachmentUploadHTTPResult {
        writes.append((path, directory))
        onProgress(5, 10)
        return answer
    }
}

@MainActor
struct FileUploadTests {
    @Test func theUploadGoesUnderTmpWithTodaysProjectAsTheLocation() async throws {
        let api = FakeUploadAPI()
        let uploader = ServerFileUploader(api: api, tmp: "/srv/tmp", directory: { "/p" })
        let path = try await uploader.upload(file: URL(fileURLWithPath: "/dev/null"), name: "report.pdf") { _, _ in }
        #expect(path == "/srv/tmp/uploads/u/report.pdf")
        let write = try #require(api.writes.first)
        #expect(write.directory == "/p")
        #expect(write.path.hasPrefix("/srv/tmp/uploads/"))
        #expect(write.path.hasSuffix("/report.pdf"))
        let folder = write.path.split(separator: "/").dropLast().last.map(String.init) ?? ""
        #expect(UUID(uuidString: folder) != nil && folder == folder.lowercased())
    }

    @Test func theAnswerIsClassified() throws {
        func answer(_ status: Int, _ body: String = "") -> AttachmentUploadHTTPResult {
            AttachmentUploadHTTPResult(statusCode: status, body: Data(body.utf8))
        }
        #expect(try ServerFileUploader.path(from: answer(200, #"{"data":{"path":"/x/y"}}"#)) == "/x/y")
        for status in [400, 404, 405, 415] {
            #expect(throws: FileUploadError.routeChanged) { try ServerFileUploader.path(from: answer(status)) }
        }
        #expect(throws: FileUploadError.failed) { try ServerFileUploader.path(from: answer(500)) }
        #expect(throws: FileUploadError.failed) { try ServerFileUploader.path(from: answer(200, #"{"data":{}}"#)) }
    }

    @Test func noUploaderWithoutTheRouteOrTmp() {
        let none: @Sendable () -> ServerConnection? = { nil }
        #expect(HomeModel.uploader(features: V2ServerFeatures(), connection: none, directory: { "/p" }) == nil)
        #expect(HomeModel.uploader(features: V2ServerFeatures(streamedUploadRoute: true), connection: none, directory: { "/p" }) == nil)
        #expect(HomeModel.uploader(features: V2ServerFeatures(streamedUploadRoute: true, uploadDirectory: "/t"), connection: none, directory: { "/p" }) != nil)
    }

    @Test func theKitClientSendsLocationDirectoryAndTheEncodedPath() async throws {
        UploadURLProtocol.reset(status: 200, body: #"{"data":{"path":"/t/uploads/u/a b+c.txt"}}"#)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UploadURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        let client = AttachmentUploadAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
        let file = FileManager.default.temporaryDirectory.appending(path: "upload-\(UUID().uuidString).txt")
        try Data("hello".utf8).write(to: file)
        let result = try await client.write(fileURL: file, path: "/t/uploads/u/a b+c.txt", directory: "/p", onProgress: { _, _ in })
        #expect(result.statusCode == 200)
        let request = try #require(UploadURLProtocol.lastRequest)
        let query = request.url?.query(percentEncoded: true) ?? ""
        #expect(request.url?.path == "/api/experimental/fs/write")
        #expect(query.contains("location%5Bdirectory%5D=%2Fp") || query.contains("location[directory]=/p") || query.contains("location%5Bdirectory%5D=/p"))
        #expect(!query.contains("&directory=") && !query.hasPrefix("directory="))
        #expect(query.contains("path=%2Ft%2Fuploads%2Fu%2Fa%20b%2Bc.txt"))
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/octet-stream")
        #expect(try ServerFileUploader.path(from: result) == "/t/uploads/u/a b+c.txt")
    }
}

/// Answers an upload with a fixed status and body.
nonisolated final class UploadURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var status = 200
    nonisolated(unsafe) static var body = ""

    static func reset(status: Int, body: String) {
        lastRequest = nil
        self.status = status
        self.body = body
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(Self.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

// MARK: The draft's files

/// Uploads on demand: each holds until `finish`, `fail` or cancellation.
@MainActor
final class FakeUploader: FileUploading {
    struct Upload {
        let name: String
        let file: URL
        let progress: @Sendable (Int64, Int64) -> Void
        let continuation: CheckedContinuation<String, any Error>
    }

    private(set) var uploads: [Upload] = []
    private(set) var started: [String] = []
    private(set) var cancelled: [String] = []
    /// Answers at once with this path pattern instead of holding.
    var instant = false

    func upload(file: URL, name: String, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> String {
        started.append(name)
        if instant { return "/srv/tmp/uploads/u\(started.count)/\(name)" }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                uploads.append(Upload(name: name, file: file, progress: onProgress, continuation: continuation))
            }
        } onCancel: {
            Task { @MainActor in self.cancel(name) }
        }
    }

    private func cancel(_ name: String) {
        guard let index = uploads.firstIndex(where: { $0.name == name }) else { return }
        cancelled.append(name)
        uploads.remove(at: index).continuation.resume(throwing: CancellationError())
    }

    func finish(_ name: String) {
        guard let index = uploads.firstIndex(where: { $0.name == name }) else { return }
        uploads.remove(at: index).continuation.resume(returning: "/srv/tmp/uploads/\(name)-folder/\(name)")
    }

    func fail(_ name: String, _ error: FileUploadError) {
        guard let index = uploads.firstIndex(where: { $0.name == name }) else { return }
        uploads.remove(at: index).continuation.resume(throwing: error)
    }

    func progress(_ name: String, sent: Int64, total: Int64) {
        uploads.first { $0.name == name }?.progress(sent, total)
    }
}

/// Files on the phone, for the picker stand-ins.
nonisolated enum TestFiles {
    static func file(_ name: String, bytes: Int = 10) -> URL {
        let folder = FileManager.default.temporaryDirectory.appending(path: "attach-files-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appending(path: name)
        try? Data(repeating: 0x41, count: bytes).write(to: url)
        return url
    }

    /// A Files pick: copied into a cache folder of its own.
    static func picked(_ name: String, mime: String = "application/octet-stream", bytes: Int = 10, cache: URL? = nil) -> PickedFile {
        let source = file(name, bytes: bytes)
        let folder = cache ?? FileManager.default.temporaryDirectory.appending(path: "attach-cache-\(UUID().uuidString)", directoryHint: .isDirectory)
        return PickedFile(name: name, mime: mime) { try PickedFile.copyIntoCache(source, name: name, folder: folder) }
    }
}

@MainActor
struct AttachFilesDraftTests {
    private func make(_ sender: FakeSender? = nil, uploader: FakeUploader? = nil) -> HomeModel {
        let folder = TestImages.folder()
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in }) { content in
            guard case let .data(data) = content else { throw PickedImage.Unreadable() }
            return try ImagePreparer.prepare(data: data, in: folder)
        }
        home.sender = sender ?? FakeSender()
        home.uploader = uploader ?? FakeUploader()
        return home
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    private func image() -> PickedImage {
        PickedImage(kind: .photo) { .data(TestImages.jpeg(width: 64, height: 48)) }
    }

    @Test func aPickedFileUploadsAtOnceAndSendReadsUploadingUntilItIsDone() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("report.pdf", mime: "application/pdf", bytes: 200)])
        #expect(home.draft.files.map(\.name) == ["report.pdf"])
        #expect(home.draft.files.first?.isUploading == true)
        #expect(home.filesPending)
        #expect(!home.canSend)
        await eventually { uploader.uploads.count == 1 }
        uploader.progress("report.pdf", sent: 90, total: 200)
        await eventually { home.draft.uploadPercent == 45 }
        #expect(AttachmentText.caption(images: 0, files: home.draft.files, uploadPercent: home.draft.uploadPercent) == "Uploading · 45%")
        uploader.finish("report.pdf")
        await eventually { !home.filesPending }
        #expect(home.draft.files.first?.path == "/srv/tmp/uploads/report.pdf-folder/report.pdf")
        #expect(home.draft.uploadPercent == nil)
        // Files alone make a message.
        #expect(home.canSend)
    }

    @Test func theCachedCopyGoesOnceUploaded() async throws {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("a.zip")])
        await eventually { uploader.uploads.count == 1 }
        let copy = try #require(uploader.uploads.first?.file)
        #expect(FileManager.default.fileExists(atPath: copy.path))
        uploader.finish("a.zip")
        await eventually { !home.filesPending }
        #expect(!FileManager.default.fileExists(atPath: copy.path))
    }

    @Test func allRunningUploadsMakeOnePercentage() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("a.zip", bytes: 100), TestFiles.picked("b.zip", bytes: 300)])
        await eventually { uploader.uploads.count == 2 }
        uploader.progress("a.zip", sent: 100, total: 100)
        uploader.progress("b.zip", sent: 100, total: 300)
        await eventually { home.draft.uploadPercent == 50 }
    }

    @Test func fourAttachmentsIsTheMostAndFilesAddOnlyTheRoomLeft() async {
        let home = make()
        home.attach([image(), image(), image()])
        home.attach(files: [TestFiles.picked("a.csv"), TestFiles.picked("b.csv")])
        #expect(home.draft.images.count == 3)
        #expect(home.draft.files.map(\.name) == ["a.csv"])
        #expect(home.toast?.title == "4 attachments is the most per message")
        home.toasts.dismiss()
        #expect(!home.attachTapped())
        #expect(home.toast?.title == "4 attachments is the most per message")
        #expect(home.draft.remainingAttachments == 0)
    }

    @Test func xWhileUploadingCancelsIt() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("big.mov")])
        await eventually { uploader.uploads.count == 1 }
        home.removeFile(home.draft.files[0].id)
        #expect(home.draft.files.isEmpty)
        await eventually { uploader.cancelled == ["big.mov"] }
        try? await Task.sleep(for: .milliseconds(30))
        // A cancel is not a failure.
        #expect(home.toast == nil)
        #expect(home.draft.isEmpty)
    }

    @Test func aFailedUploadLeavesWithANotice() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("report.pdf")])
        await eventually { uploader.uploads.count == 1 }
        uploader.fail("report.pdf", .failed)
        await eventually { home.draft.files.isEmpty }
        #expect(home.toast?.title == "Couldn’t upload report.pdf")
        #expect(home.toast?.detail == "Check the connection to your computer, then attach it again.")
        #expect(home.toast?.tone == .stop)
        #expect(home.canAttachFiles)
    }

    @Test func aChangedRouteDimsFilesForThisConnection() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("report.pdf")])
        await eventually { uploader.uploads.count == 1 }
        uploader.fail("report.pdf", .routeChanged)
        await eventually { !home.canAttachFiles }
        #expect(home.toast?.title == "Couldn’t upload report.pdf")
        // A new connection's uploader turns Files back on.
        home.uploader = FakeUploader()
        #expect(home.canAttachFiles)
    }

    @Test func filesAreDimmedWithoutAnUploader() {
        let home = make()
        home.uploader = nil
        #expect(!home.canAttachFiles)
    }

    @Test func aFileThatCantBeReadIsNotAdded() async {
        let home = make()
        home.attach(files: [PickedFile(name: "gone.txt", mime: "text/plain") { throw PickedFile.Unreadable() }])
        await eventually { home.draft.files.isEmpty }
        #expect(home.toast?.title == "Couldn’t add this file")
    }

    @Test func aFolderCantBeCopied() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "a-folder-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        #expect(throws: PickedFile.Unreadable.self) { try PickedFile.copyIntoCache(folder, name: "x", folder: TestImages.folder()) }
    }

    @Test func aBlankOrUnsafeNameIsMadeSafeOnTheTile() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [PickedFile(name: "a/b.txt", mime: "") { TestFiles.file("x.txt") }])
        #expect(home.draft.files.first?.name == "a_b.txt")
        #expect(home.draft.files.first?.mime == "application/octet-stream")
        await eventually { uploader.started == ["a_b.txt"] }
    }

    @Test func clearingTheDraftCancelsItsUploads() async {
        let uploader = FakeUploader()
        let home = make(uploader: uploader)
        home.attach(files: [TestFiles.picked("a.zip")])
        await eventually { uploader.uploads.count == 1 }
        home.clearDraft()
        #expect(home.draft.files.isEmpty)
        await eventually { uploader.cancelled == ["a.zip"] }
    }
}

// MARK: Sending files

@MainActor
struct AttachFilesSendingTests {
    private func make(_ sender: FakeSender, uploader: FakeUploader) -> HomeModel {
        let folder = TestImages.folder()
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in }) { content in
            guard case let .data(data) = content else { throw PickedImage.Unreadable() }
            return try ImagePreparer.prepare(data: data, in: folder)
        }
        home.sender = sender
        home.uploader = uploader
        return home
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    /// "Look at these", a zip, a pdf and a csv, uploaded.
    private func draft(_ home: HomeModel, _ uploader: FakeUploader) async {
        uploader.instant = true
        home.addSuggestion("Look at these")
        home.attach(files: [
            TestFiles.picked("build.zip", mime: "application/zip"),
            TestFiles.picked("report.pdf", mime: "application/pdf"),
            TestFiles.picked("data.csv", mime: "text/csv"),
        ])
        await eventually { home.canSend }
    }

    @Test func theFilesGoWithTheMessageWithTheirPaths() async {
        let sender = FakeSender()
        let uploader = FakeUploader()
        let home = make(sender, uploader: uploader)
        await draft(home, uploader)
        home.send()
        #expect(home.draft.isEmpty)
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["Look at these"])
        #expect(sender.sentFiles.first?.map(\.name) == ["build.zip", "report.pdf", "data.csv"])
        #expect(sender.sentFiles.first?.map(\.mime) == ["application/zip", "application/pdf", "text/csv"])
        #expect(sender.sentFiles.first?.map(\.path) == ["/srv/tmp/uploads/u1/build.zip", "/srv/tmp/uploads/u2/report.pdf", "/srv/tmp/uploads/u3/data.csv"])
        #expect(home.sentReader(for: "Look at these").withLine == "With 3 attachments")
    }

    @Test func filesStillGoToAModelThatCantSeeImages() async {
        let sender = FakeSender()
        let uploader = FakeUploader()
        let home = make(sender, uploader: uploader)
        home.imageInput = ImageInput(canSee: false, modelName: "Beam")
        uploader.instant = true
        home.attach(files: [TestFiles.picked("report.pdf")])
        home.attach([PickedImage(kind: .photo) { .data(TestImages.jpeg(width: 64, height: 48)) }])
        await eventually { !home.imagesPending && !home.filesPending }
        // A file alone makes a message, whatever the model.
        #expect(home.canSend)
        home.send()
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sentImages == [[]])
        #expect(sender.sentFiles.first?.map(\.name) == ["report.pdf"])
    }

    @Test func aFailedSendKeepsTheUploadedFiles() async {
        let sender = FakeSender()
        sender.fails = true
        let uploader = FakeUploader()
        let home = make(sender, uploader: uploader)
        await draft(home, uploader)
        let saved = home.draft
        home.send()
        await eventually { home.toast?.title == "Couldn’t send" }
        #expect(home.draft == saved)
        #expect(home.draft.files.allSatisfy { $0.path != nil })
        // Sending again doesn't upload them again.
        sender.fails = false
        home.send()
        await eventually { !sender.sent.isEmpty }
        #expect(uploader.started.count == 3)
        #expect(sender.sentFiles.first?.count == 3)
    }

    @Test func undoOfAQueuedMessageBringsTheFilesBackWithTheirPaths() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let uploader = FakeUploader()
        let home = make(sender, uploader: uploader)
        await draft(home, uploader)
        let saved = home.draft
        home.send()
        await eventually { home.toast?.title == "Queued · sends after this turn" }
        home.toasts.performAction()
        await eventually { home.toast?.title == "Taken back" }
        #expect(home.draft == saved)
        #expect(home.draft.files.map(\.path) == saved.files.map(\.path))
        #expect(uploader.started.count == 3)
    }

    @Test func undoDuringNowsHoldBringsTheFilesBack() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let uploader = FakeUploader()
        let gate = Gate()
        let folder = TestImages.folder()
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in await gate.wait() }) { content in
            guard case let .data(data) = content else { throw PickedImage.Unreadable() }
            return try ImagePreparer.prepare(data: data, in: folder)
        }
        home.sender = sender
        home.uploader = uploader
        await draft(home, uploader)
        let saved = home.draft
        home.sendMode = .now
        home.send()
        #expect(home.toast?.title == "Sending now…")
        home.undoHeld()
        #expect(home.draft == saved)
    }

    @Test func nowKeepsTheCorrectionPrefixFirst() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let uploader = FakeUploader()
        let home = make(sender, uploader: uploader)
        await draft(home, uploader)
        home.sendMode = .now
        home.send()
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["[Correction] Look at these"])
        #expect(sender.sentFiles.first?.count == 3)
    }

    @Test func filesAloneSendWithTheirCountAsTheNotice() async {
        let sender = FakeSender()
        let uploader = FakeUploader()
        uploader.instant = true
        let home = make(sender, uploader: uploader)
        home.attach(files: [TestFiles.picked("report.pdf")])
        await eventually { home.canSend }
        home.send()
        await eventually { home.toast?.title == "Sent · starting now" }
        #expect(home.toast?.detail == "1 file")
        #expect(sender.sent.map(\.text) == [""])
    }

    @Test func setAsGoalTakesTheFiles() async {
        let sender = FakeGoalSender()
        let uploader = FakeUploader()
        uploader.instant = true
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in })
        home.sender = sender
        home.uploader = uploader
        home.addSuggestion("Make login work offline")
        home.attach(files: [TestFiles.picked("spec.pdf")])
        await eventually { home.canUseMenuItems }
        home.setDraftAsGoal()
        await eventually { !sender.goals.isEmpty }
        #expect(sender.goals.first?.text == "Make login work offline")
        #expect(sender.goalFiles.first?.map(\.path) == ["/srv/tmp/uploads/u1/spec.pdf"])
    }
}

// MARK: Today's session

@MainActor
@Suite(.serialized)
struct AttachFilesRequestTests {
    private func service() -> OpenCodeSessionService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FilesPromptURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return OpenCodeSessionService(connection: connection, urlSession: URLSession(configuration: configuration))
    }

    @Test func thePromptHasPathLinesAndMetadata() async throws {
        FilesPromptURLProtocol.reset(#"{"data":{"id":"msg_9","sessionID":"ses_a","type":"user","payload":{"text":"x"},"delivery":"queue"}}"#)
        let files = [
            OutgoingFile(name: "build.zip", mime: "application/zip", path: "/srv/tmp/uploads/u1/build.zip"),
            OutgoingFile(name: "report.pdf", mime: "application/pdf", path: "/srv/tmp/uploads/u2/report.pdf"),
            OutgoingFile(name: "data.csv", mime: "text/csv", path: "/srv/tmp/uploads/u3/data.csv"),
        ]
        _ = try await service().prompt(sessionID: "ses_a", text: "Look at these", images: [], files: files, delivery: .queue)
        let json = try #require(JSONSerialization.jsonObject(with: FilesPromptURLProtocol.lastBody ?? Data()) as? [String: Any])
        #expect(json["text"] as? String == """
        Look at these
        Attached file: `/srv/tmp/uploads/u1/build.zip`
        Attached file: `/srv/tmp/uploads/u2/report.pdf`
        Attached file: `/srv/tmp/uploads/u3/data.csv`
        """)
        // Files are never inline.
        #expect(json["files"] == nil)
        let metadata = try #require(json["metadata"] as? [String: Any])
        #expect(metadata["displayText"] as? String == "Look at these")
        #expect((metadata["comments"] as? [Any])?.isEmpty == true)
        let attachments = try #require(metadata["attachments"] as? [[String: String]])
        #expect(attachments.map { $0["name"] } == ["build.zip", "report.pdf", "data.csv"])
        #expect(attachments.map { $0["path"] } == files.map(\.path))
    }

    @Test func nowsPrefixStaysFirstAndTheDisplayTextIsTheDrafts() async throws {
        FilesPromptURLProtocol.reset(#"{"data":{"id":"msg_9","sessionID":"ses_a","type":"user","payload":{"text":"x"},"delivery":"steer"}}"#)
        let file = OutgoingFile(name: "a.csv", mime: "text/csv", path: "/t/u/a.csv")
        _ = try await service().prompt(sessionID: "ses_a", text: "[Correction] use this", images: [], files: [file], delivery: .steer)
        let json = try #require(JSONSerialization.jsonObject(with: FilesPromptURLProtocol.lastBody ?? Data()) as? [String: Any])
        #expect(json["text"] as? String == "[Correction] use this\nAttached file: `/t/u/a.csv`")
        #expect((json["metadata"] as? [String: Any])?["displayText"] as? String == "use this")
    }

    @Test func noFilesSendsNoMetadata() async throws {
        FilesPromptURLProtocol.reset(#"{"data":{"id":"msg_1","sessionID":"ses_a","type":"user","payload":{"text":"hi"},"delivery":"steer"}}"#)
        _ = try await service().prompt(sessionID: "ses_a", text: "hi", images: [], files: [], delivery: .steer)
        let json = try #require(JSONSerialization.jsonObject(with: FilesPromptURLProtocol.lastBody ?? Data()) as? [String: Any])
        #expect(json["metadata"] == nil)
        #expect(json["text"] as? String == "hi")
    }

    @Test func theInboxReadsTheAttachmentsBack() throws {
        let json = #"{"data":[{"id":"msg_1","type":"user","payload":{"text":"Hi\nAttached file: `/t/u/report.pdf`","metadata":{"displayText":"Hi","comments":[],"attachments":[{"name":"report.pdf","mime":"application/pdf","path":"/t/u/report.pdf"},{"bad":1}]}},"delivery":"queue"}]}"#
        let inbox = try JSONDecoder().decode(APIListEnvelope<InboxItemV2>.self, from: Data(json.utf8))
        #expect(inbox.data.first?.metadata?.attachments == [PromptAttachment(name: "report.pdf", mime: "application/pdf", path: "/t/u/report.pdf")])
    }
}

@MainActor
struct AttachFilesSessionTests {
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

    private static func enqueued(_ id: String, text: String, metadata: String = "null") -> String {
        let escaped = text.replacingOccurrences(of: "\n", with: "\\n")
        return EventLines.line(
            "session.inbox.enqueued",
            #"{"sessionID":"ses_new1","inboxID":"\#(id)","item":{"type":"user","payload":{"text":"\#(escaped)","metadata":\#(metadata)},"delivery":"queue"}}"#
        )
    }

    @Test func thePhonesMessageShowsItsTextAndWithAttachments() async throws {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        let files = [
            OutgoingFile(name: "build.zip", mime: "application/zip", path: "/t/u1/build.zip"),
            OutgoingFile(name: "report.pdf", mime: "application/pdf", path: "/t/u2/report.pdf"),
        ]
        let id = try await session.send("Look", images: [], files: files, delivery: .queue)
        #expect(server.promptFiles.first == files)
        #expect(session.waitingFiles[id] == files)
        transport.send(Self.enqueued(id, text: "Look\nAttached file: `/t/u1/build.zip`\nAttached file: `/t/u2/report.pdf`"))
        await eventually { session.waitingMessages.count == 1 }
        #expect(session.waitingMessages.first?.shownText == "Look")
        transport.send(EventLines.delivered("ses_new1", id: id))
        await eventually { session.sentMessage != nil }
        #expect(session.sentMessage == "Look")
        #expect(session.sentAttachments == ["build.zip", "report.pdf"])
        #expect(AttachmentText.with(session.sentAttachments) == "With 2 attachments")
        #expect(session.waitingFiles[id] == nil)
    }

    @Test func aMessageFromAnotherDeviceIsCountedFromItsPathLines() async {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        transport.send(Self.enqueued("msg_x", text: "From the laptop\nAttached file: `/t/uploads/u/notes.txt`"))
        await eventually { session.waitingMessages.count == 1 }
        let message = session.waitingMessages[0]
        #expect(message.shownText == "From the laptop")
        #expect(message.attachedFileNames == ["notes.txt"])
        #expect(AttachmentText.count(images: message.imageCount, files: message.attachedFileNames.count) == "1 file")
        transport.send(EventLines.delivered("ses_new1", id: "msg_x"))
        await eventually { session.sentMessage != nil }
        #expect(session.sentMessage == "From the laptop")
        #expect(session.sentAttachments == ["notes.txt"])
    }

    @Test func theMetadatasNamesWin() async {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        transport.send(Self.enqueued(
            "msg_y",
            text: "Attached file: `/t/u/x1`",
            metadata: #"{"attachments":[{"name":"Quarterly report.pdf","mime":"application/pdf","path":"/t/u/x1"}]}"#
        ))
        await eventually { session.waitingMessages.count == 1 }
        #expect(session.waitingMessages[0].shownText == "")
        #expect(session.waitingMessages[0].attachedFileNames == ["Quarterly report.pdf"])
        transport.send(EventLines.delivered("ses_new1", id: "msg_y"))
        await eventually { !session.sentAttachments.isEmpty }
        #expect(session.sentMessage == "")
        #expect(AttachmentText.with(session.sentAttachments) == "With Quarterly report.pdf")
    }

    @Test func cancellingDropsTheFileTiles() async throws {
        let server = FakeSessionServer()
        let (session, _) = make(server)
        await session.open()
        let id = try await session.send("", images: [], files: [OutgoingFile(name: "a", mime: "", path: "/t/a")], delivery: .queue)
        #expect(session.waitingFiles[id]?.count == 1)
        try await session.cancelWaiting(id)
        #expect(session.waitingFiles[id] == nil)
    }
}

@MainActor
struct AttachFilesGoalTests {
    @Test func theGoalCommandCarriesThePathLines() async {
        let h = GoalHarness()
        await h.open()
        let files = [OutgoingFile(name: "spec.pdf", mime: "application/pdf", path: "/t/u/spec.pdf")]
        #expect(await h.session.setGoal("Make login work offline", files: files, fromDraft: true) == .done)
        #expect(h.goals.commands.last == "Make login work offline\nAttached file: `/t/u/spec.pdf`")
        #expect(h.session.sentMessage == "Make login work offline")
    }

    @Test func theGoalLineAndSheetStripThem() {
        let goal = TodayGoal(id: "g1", objective: "Ship it\nAttached file: `/t/u/spec.pdf`", status: .active)
        #expect(goal.objective == "Ship it")
    }
}

/// Records the prompt request, as `PromptURLProtocol` does, for this file's suite alone (suites run
/// side by side).
nonisolated final class FilesPromptURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastBody: Data?
    nonisolated(unsafe) static var reply = ""

    static func reset(_ reply: String) {
        lastBody = nil
        self.reply = reply
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
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
