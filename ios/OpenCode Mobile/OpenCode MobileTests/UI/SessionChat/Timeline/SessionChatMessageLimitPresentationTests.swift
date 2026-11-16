import Foundation
import SwiftUI
import Testing
import UIKit
import XCTest
@testable import OpenCode_Mobile

/// A cut user message in the timeline (#1043): the shown part and its row, with the whole text
/// kept for Copy message, Select text and Show full message.
@Suite struct SessionChatMessageLimitPresentationTests {
    private static let longText = (1 ... 400).map { "2026-01-01T00:00:00Z INFO worker \($0) ok" }.joined(separator: "\n")

    private static func message(_ role: MessageRole, _ text: String = longText) -> Message {
        Message(id: "msg-1", role: role, text: text, model: nil, mode: nil, sentAtEpochMs: 1)
    }

    @Test func longUserMessage_showsTheCutTextWithTheCounts() throws {
        let blocks = SessionChatTimelineBlocks.present(message: Self.message(.user), commands: [])

        let cut = try #require(MessageTextLimit.cut(Self.longText))
        guard case let .limitedText(_, text, limit) = try #require(blocks.first) else {
            Issue.record("expected a limited text block, got \(blocks)")
            return
        }
        #expect(blocks.count == 1)
        #expect(text == cut.visibleText)
        #expect(
            limit == SessionChatTextLimit(shownCharacters: cut.shownCharacters, totalCharacters: Self.longText.utf16.count)
        )
        #expect(limit.shownCharacters < limit.totalCharacters)
    }

    @Test func emojiUserMessage_showsTheSameCountsAsAndroid() throws {
        // Same vector as Android: the row counts UTF-16 units, and the cut keeps whole emoji.
        let text = "a" + String(repeating: "😀", count: 700)
        let blocks = SessionChatTimelineBlocks.present(message: Self.message(.user, text), commands: [])

        guard case let .limitedText(_, shown, limit) = try #require(blocks.first) else {
            Issue.record("expected a limited text block, got \(blocks)")
            return
        }
        #expect(shown == "a" + String(repeating: "😀", count: 499) + "…")
        #expect(limit == SessionChatTextLimit(shownCharacters: 999, totalCharacters: 1_401))
    }

    @Test func shortUserMessage_keepsItsText() {
        let blocks = SessionChatTimelineBlocks.present(message: Self.message(.user, "Run the tests"), commands: [])

        #expect(blocks.map(\.textContent?.text) == ["Run the tests"])
    }

    @Test func assistantMessageOfTheSameLength_isUntouched() {
        let blocks = SessionChatTimelineBlocks.present(message: Self.message(.assistant), commands: [])

        #expect(blocks.map(\.textContent?.text) == [Self.longText])
    }

    @Test func messageActionsAndFullMessage_getTheWholeText() {
        let row = SessionChatRow(
            id: "msg-1",
            role: .user,
            text: Self.longText,
            metadataLabel: "",
            blocks: SessionChatTimelineBlocks.present(message: Self.message(.user), commands: [])
        )

        let actions = row.messageActionsTarget(showsRevertMessage: true)
        #expect(actions.text == Self.longText)
        #expect(actions.showsRevertMessage)
        #expect(row.fullMessageTarget == SessionChatSelectTextTarget(id: "msg-1", text: Self.longText, isFullMessage: true))
    }

    @Test func cutUserMessage_parsesOnlyItsShownPart() async throws {
        let marker = UUID().uuidString
        let markdown = "# Title \(marker)\n\n" + (1 ... 60).map { "- item \($0) \(marker)" }.joined(separator: "\n")
        let preparer = MessageContentPreparer()

        let (inputs, cut) = await MarkdownInputRecording.record {
            await preparer.prepareMessage(Self.message(.user, markdown))
        }

        let shown = try #require(MessageTextLimit.cut(markdown)).visibleText
        let mine = inputs.filter { $0.text.contains(marker) }
        #expect(mine.contains { $0.stage == .detect })
        #expect(mine.contains { $0.stage == .parse })
        #expect(mine.allSatisfy { $0.text == shown })
        let document = try #require(cut.renderedMarkdown)
        #expect(document.blocks.contains { if case .heading = $0 { true } else { false } })
        guard case let .bulletList(items) = try #require(document.blocks.last) else {
            Issue.record("expected a list, got \(document.blocks)")
            return
        }
        #expect(items.count < 60)
    }

    @Test func cutUserMessage_rendersLikeAnUncutOneUpToTheCut() throws {
        let markdown = "# Title\n\n" + (1 ... 60).map { "- item \($0)" }.joined(separator: "\n")
        let shown = try #require(MessageTextLimit.cut(markdown)).visibleText
        let document = OpenCodeRenderedMarkdownParser.parse(shown, structureOnly: true)

        let frame = DisplayFrameBuilder.build(
            sessionKey: "s",
            frameRevision: 1,
            sourceRevision: 1,
            displayMessages: [DisplayMessage(message: Self.message(.user, markdown), renderedMarkdown: document)]
        )

        let body = try #require(frame.messages.first)
        #expect(body.renderedMarkdown?.sourceHash == document.sourceHash)
        guard case let .limitedText(_, text, _) = try #require(body.blocks.first) else {
            Issue.record("expected a limited text block, got \(body.blocks)")
            return
        }
        #expect(text == shown)
    }

    @Test func shownPartDocument_neverLandsOnAnUncutText() throws {
        // A long command template collapses to a short `[/review] args`: the message text is
        // cut, its bubble is not, so the document of the cut part must not reach the bubble.
        let template = (1 ... 60).map { "- check \($0)" }.joined(separator: "\n")
        let text = template + "\n\nthe parser"
        let shown = try #require(MessageTextLimit.cut(text)).visibleText
        let document = OpenCodeRenderedMarkdownParser.parse(shown, structureOnly: true)

        let frame = DisplayFrameBuilder.build(
            sessionKey: "s",
            frameRevision: 1,
            sourceRevision: 1,
            displayMessages: [DisplayMessage(message: Self.message(.user, text), renderedMarkdown: document)],
            serverCommands: [ServerCommand(name: "review", template: template)]
        )

        let body = try #require(frame.messages.first)
        #expect(body.blocks.map(\.textContent?.text) == ["[/review] the parser"])
        #expect(body.renderedMarkdown == nil)
    }

    @Test func multiMegabyteUserMessage_reachesTheDetectorAndParserOnlyAsItsShownPart() async throws {
        let marker = UUID().uuidString
        let text = (1 ... 10).map { "## Step \($0) \(marker)" }.joined(separator: "\n") + "\n"
            + (1 ... 60_000).map { "2026-01-01T00:00:00Z INFO worker \($0) ok" }.joined(separator: "\n")
            + "\ntail-\(marker)"
        #expect(text.utf8.count > 2_000_000)
        let message = Self.message(.user, text)
        let preparer = MessageContentPreparer()

        let (inputs, _) = await MarkdownInputRecording.record {
            _ = await preparer.prepareMessage(message, mode: .historyPlainFirst)
            _ = await preparer.prepareMessage(message, mode: .historyEnrichment)
            _ = await preparer.prepareMessages([message], mode: .final)
            _ = shouldPlainFirstHistoryPublish(messages: [message], previousByID: [:])
            _ = newestMarkdownViewportIDs(messages: [message])
            _ = hasCompletePreparedMarkdownCache(messages: [message], previousByID: [:])
        }

        let mine = inputs.filter { $0.text.contains(marker) }
        #expect(!mine.isEmpty)
        #expect(mine.allSatisfy { $0.text.utf16.count <= 2_000 })
        #expect(!mine.contains { $0.text.contains("tail-\(marker)") })
    }

    @Test func multiMegabyteUserMessage_isCountedAndRevisedWithoutWalkingItsText() throws {
        let text = (1 ... 60_000).map { "2026-01-01T00:00:00Z INFO worker \($0) ok" }.joined(separator: "\n")
        let blocks = SessionChatTimelineBlocks.present(message: Self.message(.user, text), commands: [])
        guard case let .limitedText(_, shown, limit) = try #require(blocks.first) else {
            Issue.record("expected a limited text block, got \(blocks)")
            return
        }

        // UTF-16 counts (as Android), and a revision of the shown part plus the UTF-8 length.
        #expect(limit.totalCharacters == text.utf16.count)
        #expect(limit.shownCharacters == shown.utf16.count)
        let revision = ContentRevision.of(text, blocks: blocks)
        #expect(revision.textLength == text.utf8.count)
        #expect(revision.textHash == stableMarkdownSourceHash(shown))
        // The optimistic row and the server echo of the same text get the same revision.
        let echo = SessionChatTimelineBlocks.present(message: Self.message(.user, text), commands: [])
        #expect(ContentRevision.of(text, blocks: echo) == revision)
    }

    @Test func cutInsideACodeFence_rendersACodeBlockToTheCut() throws {
        let markdown = "Look at this:\n\n```swift\n" + (1 ... 60).map { "let value\($0) = \($0)" }.joined(separator: "\n")
            + "\n```\n\nDone"
        let shown = try #require(MessageTextLimit.cut(markdown)).visibleText

        let document = OpenCodeRenderedMarkdownParser.parse(shown, structureOnly: true)

        guard case let .codeBlock(_, code, _) = try #require(document.blocks.last) else {
            Issue.record("expected a code block, got \(document.blocks)")
            return
        }
        #expect(code.contains("let value1 = 1"))
        #expect(!code.contains("let value60 = 60"))
        #expect(!shown.contains("Done"))
    }

    @Test func midLineCut_keepsItsMarkerInTheLastListItem() throws {
        let markdown = (1 ... 20).map { "- item \($0)" }.joined(separator: "\n") + "\n- "
            + (1 ... 200).map { "word\($0)" }.joined(separator: " ")
        let shown = try #require(MessageTextLimit.cut(markdown)).visibleText
        #expect(shown.hasSuffix("…"))

        let document = OpenCodeRenderedMarkdownParser.parse(shown, structureOnly: true)

        #expect(document.blocks.count == 1)
        guard case let .bulletList(items) = try #require(document.blocks.first),
              case let .text(last) = try #require(items.last?.last)
        else {
            Issue.record("expected one list, got \(document.blocks)")
            return
        }
        #expect(items.count == 21)
        #expect(last.hasSuffix("…"))
    }

    @Test func assistantMessageOfTheSameLength_isStillParsedWhole() async {
        let markdown = "# Title\n\n" + (1 ... 60).map { "- item \($0)" }.joined(separator: "\n")

        let reply = await MessageContentPreparer().prepareMessage(Self.message(.assistant, markdown))

        guard case let .bulletList(items) = reply.renderedMarkdown?.blocks.last else {
            Issue.record("expected a list")
            return
        }
        #expect(items.count == 60)
    }

    @Test func largeText_opensInTheSelfScrollingBody() {
        #expect(!SessionChatSelectText.scrollsItself(Self.longText))
        #expect(SessionChatSelectText.scrollsItself(String(repeating: "x\n", count: 20_000)))
    }
}

/// Records what the markdown detector and parser are given while `body` runs. Tests run in
/// parallel, so each test filters the inputs by a marker of its own.
enum MarkdownInputRecording {
    struct Input: Sendable {
        let stage: MarkdownInputProbe.Stage
        let text: String
    }

    private final class Box: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [Input] = []

        func append(_ input: Input) {
            lock.lock()
            values.append(input)
            lock.unlock()
        }

        var all: [Input] {
            lock.lock()
            defer { lock.unlock() }
            return values
        }
    }

    static func record<T>(_ body: () async throws -> T) async rethrows -> ([Input], T) {
        let box = Box()
        let id = MarkdownInputProbe.observe { stage, text in box.append(Input(stage: stage, text: text)) }
        defer { MarkdownInputProbe.stopObserving(id) }
        let value = try await body()
        return (box.all, value)
    }
}

/// The crash in #1043: a pasted log of 190,000 lines made one timeline row about 3.6 million
/// points tall, which UIKit rejects. The row must now size to the cut text.
@MainActor
final class SessionChatLongUserMessageRowHeightTests: XCTestCase {
    func testUserMessageOf190000Lines_hasASmallRow() throws {
        let text = Array(repeating: "x", count: 190_000).joined(separator: "\n")
        let message = Message(id: "msg-long", role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 1)
        let row = SessionChatRow(
            id: message.id,
            role: .user,
            text: text,
            metadataLabel: "",
            blocks: SessionChatTimelineBlocks.present(message: message, commands: [])
        )
        let frame = TimelineFrame(
            frameRevision: 1,
            rows: [],
            insertedRowIDs: [row.id],
            changedRowIDs: [],
            removedRowIDs: [],
            layoutChangedRowIDs: [],
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: true
        )
        let host = UIHostingController(
            rootView: SessionChatTimelineView(
                props: SessionChatTimelineViewProps(
                    timeline: .ready,
                    messages: [row],
                    timelineFrame: frame,
                    isRefreshing: false,
                    errorMessage: nil,
                    onRefresh: {},
                    onMessageActionsTap: { _ in }
                )
            )
            .openCodeTheme()
        )
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 440, height: 956))
        window.windowScene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        for _ in 0 ..< 4 {
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.15))
        }

        let cell = try XCTUnwrap(Self.hostCells(in: host.view).first { $0.itemID == row.id })
        XCTAssertGreaterThan(cell.frame.height, 100)
        XCTAssertLessThan(cell.frame.height, 1_000)
    }

    private static func hostCells(in view: UIView) -> [MessageListTimelineHostCell] {
        var cells: [MessageListTimelineHostCell] = []
        if let cell = view as? MessageListTimelineHostCell { cells.append(cell) }
        for subview in view.subviews {
            cells.append(contentsOf: hostCells(in: subview))
        }
        return cells
    }
}
