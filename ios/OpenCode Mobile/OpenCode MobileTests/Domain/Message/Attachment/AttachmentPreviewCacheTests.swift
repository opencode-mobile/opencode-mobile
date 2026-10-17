import Foundation
import Testing
import UIKit
@testable import OpenCode_Mobile

struct AttachmentPreviewCacheTests {
    @Test func warm_largeImageDataURL_resolvesPreview() throws {
        let cache = AttachmentPreviewCache()
        let png = try #require(Self.pngData(size: CGSize(width: 1_200, height: 900)))
        let dataURL = try #require(AttachmentDataURL.make(data: png, mime: "image/png"))
        let file = FilePart(id: "msg-1:file:0", filename: "Photo 1.png", mime: "image/png", kind: .image, dataURL: dataURL)

        #expect(cache.resolved(file).previewData == nil)
        cache.warm([Self.message(parts: [.file(file)])])

        let preview = try #require(cache.resolved(file).previewData)
        let image = try #require(UIImage(data: preview))
        #expect(max(image.size.width, image.size.height) <= 256)
    }

    @Test func resolved_keepsExistingPreviewAndSkipsNonImages() throws {
        let cache = AttachmentPreviewCache()
        let local = FilePart(
            id: "local",
            filename: "a.png",
            mime: "image/png",
            kind: .image,
            dataURL: "",
            previewData: Data([1, 2, 3])
        )
        let text = FilePart(
            id: "notes",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,aGk="
        )
        cache.warm([Self.message(parts: [.file(local), .file(text)])])

        #expect(cache.resolved(local).previewData == Data([1, 2, 3]))
        #expect(cache.resolved(text).previewData == nil)
    }

    @Test func warm_undecodableImage_staysWithoutPreview() {
        let cache = AttachmentPreviewCache()
        let file = FilePart(
            id: "broken",
            filename: "broken.png",
            mime: "image/png",
            kind: .image,
            dataURL: "data:image/png;base64,aGVsbG8="
        )
        cache.warm([Self.message(parts: [.file(file)])])
        #expect(cache.resolved(file).previewData == nil)
    }

    @Test func previewWarmCandidates_skipRevertedTail() {
        let messages = (1...9).map { index in
            DisplayMessage(
                message: Message(id: "msg-\(index)", role: .user, text: "", model: nil, mode: nil, sentAtEpochMs: Int64(index))
            )
        }
        // Reverting from msg-5 hides msg-5...msg-9; the newest shown rows are msg-1...msg-4.
        let visible = SessionChatTimeline.hidingReverted(messages, from: "msg-5")
        let candidates = SessionChatTimeline.previewWarmCandidates(visible)
        #expect(candidates.map(\.id) == ["msg-1", "msg-2", "msg-3", "msg-4"])
    }

    private static func message(parts: [MessagePart]) -> Message {
        Message(id: "msg-1", role: .user, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: 1)
    }

    private static func pngData(size: CGSize) -> Data? {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).pngData { context in
            for index in 0..<40 {
                UIColor(hue: CGFloat(index) / 40, saturation: 1, brightness: 1, alpha: 1).setFill()
                context.fill(CGRect(x: CGFloat(index) * 30, y: 0, width: 30, height: size.height))
            }
        }
    }
}
