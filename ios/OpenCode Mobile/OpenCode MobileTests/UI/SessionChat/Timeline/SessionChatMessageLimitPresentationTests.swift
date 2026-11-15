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
        #expect(limit == SessionChatTextLimit(shownCharacters: cut.shownCharacters, totalCharacters: Self.longText.count))
        #expect(limit.shownCharacters < limit.totalCharacters)
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

    @Test func cutUserMessage_isNotParsedAsMarkdown() async {
        let markdown = "# Title\n\n" + (1 ... 60).map { "- item \($0)" }.joined(separator: "\n")
        let preparer = MessageContentPreparer()

        let cut = await preparer.prepareMessage(Self.message(.user, markdown))
        let reply = await preparer.prepareMessage(Self.message(.assistant, markdown))

        #expect(cut.renderedMarkdown == nil)
        #expect(reply.renderedMarkdown != nil)
    }

    @Test func largeText_opensInTheSelfScrollingBody() {
        #expect(!SessionChatSelectText.scrollsItself(Self.longText))
        #expect(SessionChatSelectText.scrollsItself(String(repeating: "x\n", count: 20_000)))
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
