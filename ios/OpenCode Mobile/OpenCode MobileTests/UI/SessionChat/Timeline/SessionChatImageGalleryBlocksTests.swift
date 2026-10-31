import Foundation
import Testing
@testable import OpenCode_Mobile

/// Standard timeline: images become gallery blocks under the tool, group or message that
/// produced them (#898).
struct SessionChatImageGalleryBlocksTests {
    private static let png = ToolImageMappingTests.pngDataURL

    // MARK: - Tool images

    @Test(arguments: [1, 2, 3, 4, 5, 18])
    func toolImagesFollowTheirCardInOneGallery(count: Int) throws {
        let blocks = build(parts: [tool("call_1", images: count)])

        #expect(blocks.count == 2)
        guard case .toolCard(let cardID, let card) = blocks[0],
              case .imageGallery(let galleryID, let gallery) = blocks[1]
        else {
            Issue.record("Expected a tool card then a gallery, got \(blocks)")
            return
        }
        #expect(galleryID == PresentationBlockId.imageGallery([cardID]))
        #expect(gallery.source == .tool)
        #expect(gallery.images.count == count)
        #expect(card.images.count == count)
    }

    @Test func toolWithoutImagesHasNoGallery() {
        let blocks = build(parts: [tool("call_1", images: 0)])
        #expect(blocks.count == 1)
        #expect(!blocks.contains { if case .imageGallery = $0 { true } else { false } })
    }

    @Test func toolGroupShowsEveryMembersImagesUnderTheGroupCard() throws {
        let blocks = build(
            parts: [
                tool("call_1", name: "read", images: 2),
                tool("call_2", name: "read", images: 0),
                tool("call_3", name: "read", images: 3),
            ]
        )
        #expect(blocks.count == 2)
        guard case .toolGroup(let groupID, let group) = blocks[0],
              case .imageGallery(let galleryID, let gallery) = blocks[1]
        else {
            Issue.record("Expected a tool group then a gallery, got \(blocks)")
            return
        }
        #expect(galleryID == PresentationBlockId.imageGallery([groupID]))
        #expect(gallery.images.map(\.id) == [
            "call_1:file:0", "call_1:file:1", "call_3:file:0", "call_3:file:1", "call_3:file:2",
        ])
        // The full-screen group view lists each member's images with its card.
        #expect(group.members.map(\.images.count) == [2, 0, 3])
    }

    @Test func toolOnlyStepsWithImagesStillMergeIntoOneGroup() {
        let bodies = ["a1", "a2"].map { id in
            body(id, parts: [tool("\(id)_call", name: "bash", images: 1)], collapse: true)
        }
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: true
        )
        #expect(merged.count == 1)
        let blocks = merged[0].blocks
        #expect(blocks.count == 2)
        guard case .toolGroup = blocks.first, case .imageGallery(_, let gallery) = blocks.last else {
            Issue.record("Expected a merged group and its gallery, got \(blocks)")
            return
        }
        #expect(gallery.images.map(\.id) == ["a1_call:file:0", "a2_call:file:0"])
    }

    @Test func textBetweenToolsKeepsEachToolsGalleryUnderItsCard() {
        let blocks = build(
            parts: [
                tool("call_1", name: "bash", images: 1),
                .text(TextPart(id: "t1", text: "Now the other one", isStreaming: false)),
                tool("call_2", name: "bash", images: 2),
            ]
        )
        let kinds = blocks.map(kind)
        #expect(kinds == ["card", "gallery", "text", "card", "gallery"])
    }

    // MARK: - Attached images

    @Test func userWithOneImageKeepsTheAttachmentBlock() {
        let blocks = build(role: .user, parts: [text("t1", "Look"), file("f1")])
        #expect(blocks.map(kind) == ["text", "attachment"])
    }

    @Test(arguments: [2, 3, 4, 5])
    func userImagesFormOneGallery(count: Int) throws {
        let files = (0..<count).map { file("f\($0)") }
        let blocks = build(role: .user, parts: [text("t1", "Look")] + files)
        #expect(blocks.map(kind) == ["text", "gallery"])
        guard case .imageGallery(let id, let gallery) = blocks.last else { return }
        #expect(gallery.source == .user)
        #expect(gallery.images.map(\.id) == (0..<count).map { "f\($0)" })
        #expect(id == PresentationBlockId.imageGallery((0..<count).map { "f\($0)" }))
    }

    @Test func hiddenTextBetweenImagesDoesNotSplitTheGallery() {
        let blocks = build(
            role: .user,
            parts: [
                file("f1"),
                .text(TextPart(id: "syn", text: "decoded", isStreaming: false, isSynthetic: true)),
                file("f2"),
            ]
        )
        #expect(blocks.map(kind) == ["gallery"])
    }

    @Test func pdfBreaksAnImageRun() {
        let blocks = build(
            role: .user,
            parts: [file("f1"), file("f2"), file("doc", mime: "application/pdf", kind: .pdf), file("f3")]
        )
        #expect(blocks.map(kind) == ["gallery", "attachment", "attachment"])
    }

    @Test func cachedImagesWithNothingToLoadStayAttachmentCards() {
        let cached = FilePart(id: "f1", filename: "a.png", mime: "image/png", kind: .image, dataURL: "")
        let cached2 = FilePart(id: "f2", filename: "b.png", mime: "image/png", kind: .image, dataURL: "")
        let blocks = build(role: .user, parts: [.file(cached), .file(cached2)])
        #expect(blocks.map(kind) == ["attachment", "attachment"])
    }

    @Test func assistantImagesGalleryFollowsTheText() {
        let blocks = build(parts: [text("t1", "Before and after"), file("f1"), file("f2")])
        #expect(blocks.map(kind) == ["text", "gallery"])
        guard case .imageGallery(_, let gallery) = blocks.last else { return }
        #expect(gallery.source == .assistant)
    }

    @Test func userGalleryCountsAsAttachmentContent() {
        let user = SessionChatTimelineBlock.imageGallery(
            blockID: "i:x",
            gallery: SessionChatImageGalleryBlock(source: .user, images: [])
        )
        let tool = SessionChatTimelineBlock.imageGallery(
            blockID: "i:y",
            gallery: SessionChatImageGalleryBlock(source: .tool, images: [])
        )
        #expect(user.isAttachmentContent)
        #expect(!tool.isAttachmentContent)
    }

    // MARK: - Helpers

    private func build(role: MessageRole = .assistant, parts: [MessagePart], collapse: Bool = false) -> [SessionChatTimelineBlock] {
        guard case .ok(let blocks) = SessionChatTimelineBlocks.build(
            role: role,
            parts: parts,
            collapseConsecutiveToolCalls: collapse
        ) else {
            Issue.record("Expected structured blocks")
            return []
        }
        return blocks
    }

    private func body(_ id: String, parts: [MessagePart], collapse: Bool) -> DisplayMessageBody {
        DisplayMessageBody(
            message: Message(id: id, role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: nil),
            isStreaming: false,
            blocks: build(parts: parts, collapse: collapse),
            renderedMarkdown: nil
        )
    }

    private func kind(_ block: SessionChatTimelineBlock) -> String {
        switch block {
        case .text: "text"
        case .attachment: "attachment"
        case .toolCard: "card"
        case .toolGroup: "group"
        case .imageGallery: "gallery"
        default: "other"
        }
    }

    private func text(_ id: String, _ value: String) -> MessagePart {
        .text(TextPart(id: id, text: value, isStreaming: false))
    }

    private func file(_ id: String, mime: String = "image/png", kind: AttachmentKind = .image) -> MessagePart {
        .file(FilePart(id: id, filename: "\(id).png", mime: mime, kind: kind, dataURL: kind == .image ? Self.png : ""))
    }

    private func tool(_ id: String, name: String = "browser_screenshot", images: Int) -> MessagePart {
        .tool(
            ToolPart(
                id: id,
                toolName: name,
                status: .completed,
                input: [:],
                output: "ok",
                images: (0..<images).map { index in
                    FilePart(
                        id: MessageDomainAdapter.toolImageID(ownerID: id, index: index),
                        filename: "shot-\(index).png",
                        mime: "image/png",
                        kind: .image,
                        dataURL: Self.png
                    )
                }
            )
        )
    }
}

@MainActor
struct SessionChatImageViewerStateTests {
    private let images = (0..<3).map { index in
        FilePart(
            id: "f\(index)",
            filename: "f\(index).png",
            mime: "image/png",
            kind: .image,
            dataURL: ToolImageMappingTests.pngDataURL
        )
    }

    @Test func openPageAndCloseTheViewer() {
        let timeline = makeTimeline()
        #expect(timeline.state.imageViewer == nil)

        timeline.openImageViewer(images: images, index: 1)
        #expect(timeline.state.imageViewer == SessionChatImageViewerState(images: images, index: 1))

        timeline.showImageViewerPage(2)
        #expect(timeline.state.imageViewer?.index == 2)

        timeline.closeImageViewer()
        #expect(timeline.state.imageViewer == nil)
    }

    @Test func outOfRangeRequestsAreIgnored() {
        let timeline = makeTimeline()
        timeline.openImageViewer(images: images, index: 3)
        #expect(timeline.state.imageViewer == nil)
        timeline.openImageViewer(images: [], index: 0)
        #expect(timeline.state.imageViewer == nil)

        timeline.openImageViewer(images: images, index: 0)
        timeline.showImageViewerPage(5)
        timeline.showImageViewerPage(-1)
        #expect(timeline.state.imageViewer?.index == 0)
    }

    @Test func pagingWithoutAnOpenViewerDoesNothing() {
        let timeline = makeTimeline()
        timeline.showImageViewerPage(1)
        timeline.closeImageViewer()
        #expect(timeline.state.imageViewer == nil)
    }

    private func makeTimeline() -> SessionChatTimeline {
        SessionChatTimeline(sessionID: "ses-1", worktree: "/Users/example/project", messageRepository: ViewerMessageRepository())
    }
}

private final class ViewerMessageRepository: MessageRepository, @unchecked Sendable {
    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] { [] }
    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] { [] }
    func loadOlderDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] { [] }
    func hasOlderMessages(sessionID: String, directory: String?) -> Bool { false }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        DisplayMessage(message: Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 0))
    }

    func clearMessageCache() {}
    func listMessages(sessionID: String, directory: String?) async throws -> [Message] { [] }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        throw MessageError.transport("not found")
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        Message(id: "sent", role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 0)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {}
}
