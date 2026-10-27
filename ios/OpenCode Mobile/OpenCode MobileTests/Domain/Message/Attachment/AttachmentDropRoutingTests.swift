import Foundation
import Testing
import UniformTypeIdentifiers
@testable import OpenCode_Mobile

struct AttachmentDropRoutingTests {
    private struct Vectors: Decodable {
        struct Item: Decodable {
            let kind: String
            let filename: String?
            let byteCount: Int64?
        }

        struct DraftFile: Decodable {
            let filename: String
            let byteCount: Int64
        }

        struct Context: Decodable {
            let imagesSupported: Bool?
            let pdfSupported: Bool?
            let uploadAvailable: Bool?
            let draft: [DraftFile]?
        }

        struct RouteExpectation: Decodable {
            let accepted: Bool?
            let skipped: String?
        }

        struct Route: Decodable {
            let name: String
            let item: Item
            let context: Context?
            let expected: RouteExpectation
        }

        struct SummaryExpectation: Decodable {
            let title: String
            let subtitle: String
            let previews: [String]
            let rejected: Bool
        }

        struct Summary: Decodable {
            let name: String
            let items: [Item]
            let context: Context?
            let countKnown: Bool?
            let expected: SummaryExpectation?
        }

        struct PastedName: Decodable {
            let name: String
            let draft: [String]
            let expected: String
        }

        let routes: [Route]
        let summaries: [Summary]
        let pastedImageNames: [PastedName]
    }

    /// The vectors Android reads too: `shared/attachment-drop/attachment-drop-cases.json`.
    private static func vectors() throws -> Vectors {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let relative = "shared/attachment-drop/attachment-drop-cases.json"
        for _ in 0 ..< 12 {
            let candidate = directory.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: candidate))
            }
            directory.deleteLastPathComponent()
        }
        throw CocoaError(.fileNoSuchFile)
    }

    private static func item(_ item: Vectors.Item) throws -> AttachmentDropItem {
        let kind = try #require(AttachmentDropKind(rawValue: item.kind))
        return AttachmentDropItem(kind: kind, filename: item.filename, byteCount: item.byteCount)
    }

    private static func context(_ context: Vectors.Context?) -> AttachmentDropContext {
        AttachmentDropContext(
            imagesSupported: context?.imagesSupported ?? true,
            pdfSupported: context?.pdfSupported ?? true,
            uploadAvailable: context?.uploadAvailable ?? false,
            draft: (context?.draft ?? []).enumerated().map { index, file in
                Attachment(
                    id: "draft-\(index)",
                    filename: file.filename,
                    mime: "application/octet-stream",
                    kind: .file,
                    dataURL: "",
                    byteCount: file.byteCount
                )
            }
        )
    }

    @Test func sharedRouteVectors() throws {
        let cases = try Self.vectors().routes
        #expect(!cases.isEmpty)
        for vector in cases {
            let expected: AttachmentDropRoute = vector.expected.skipped.map { .skipped(reason: $0) } ?? .accepted
            #expect(
                AttachmentDropRouting.route(try Self.item(vector.item), context: Self.context(vector.context)) == expected,
                "\(vector.name)"
            )
        }
    }

    @Test func sharedSummaryVectors() throws {
        let cases = try Self.vectors().summaries
        #expect(!cases.isEmpty)
        for vector in cases {
            let summary = AttachmentDropRouting.summary(
                try vector.items.map(Self.item),
                context: Self.context(vector.context),
                countKnown: vector.countKnown ?? true
            )
            let expected = try vector.expected.map { fields in
                AttachmentDropSummary(
                    title: fields.title,
                    subtitle: fields.subtitle,
                    previews: try fields.previews.map { try #require(AttachmentDropKind(rawValue: $0)) },
                    rejected: fields.rejected
                )
            }
            #expect(summary == expected, "\(vector.name)")
        }
    }

    @Test func itemProviderKindsFollowTheirTypes() {
        func kind(_ types: [UTType], _ name: String?) -> AttachmentDropKind? {
            AttachmentItemProviders.kind(contentTypes: types, suggestedName: name)
        }
        #expect(kind([.folder], "designs") == .folder)
        #expect(kind([.png, .url], nil) == .image)
        #expect(kind([.heic], "IMG_0001.HEIC") == .image)
        #expect(kind([.pdf], "spec.pdf") == .pdf)
        #expect(kind([.plainText], "device.log") == .text)
        #expect(kind([.zip], "dump.zip") == .file)
        // A text selection or a link is text, never an attachment.
        #expect(kind([.utf8PlainText, .html], nil) == nil)
        #expect(kind([.url], nil) == nil)
        #expect(kind([.url, .plainText], "example.com") == nil)
        #expect(kind([.fileURL, .plainText], "notes.txt") == .text)
    }

    @Test func sharedPastedImageNameVectors() throws {
        let cases = try Self.vectors().pastedImageNames
        #expect(!cases.isEmpty)
        for vector in cases {
            #expect(AttachmentDropRouting.pastedImageName(draftFilenames: vector.draft) == vector.expected, "\(vector.name)")
        }
    }
}
