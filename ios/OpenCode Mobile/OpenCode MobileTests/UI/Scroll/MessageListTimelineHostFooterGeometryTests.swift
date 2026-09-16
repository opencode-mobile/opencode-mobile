import Foundation
import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

/// Host footer contract: a collapsed footer is not a row. Collapse is a
/// remove packet; the next send re-inserts. Zero-height cells are never
/// remasured, so expand-in-place is not a supported path.
private enum FooterGeometryTestDefaults {
    static let workingID = SessionChatStatusChrome.workingRowID
    static let pinTolerance: CGFloat = 1.5
    static let interItemSpacing: CGFloat = 8
}

@Suite(.serialized)
@MainActor
struct MessageListTimelineHostFooterGeometryTests {

    @Test("footerInsertAfterPinnedHistory", arguments: [24, 2])
    func footerInsertAfterPinnedHistory(messageCount: Int) {
        let harness = FooterGeometryHarness(messageCount: messageCount)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history")
        harness.applyFooterInsert(label: "Working")
        harness.assertPinnedOnComposerLine(step: "footer-insert-Working")
    }

    @Test("footerLabelChange", arguments: [24, 2])
    func footerLabelChange(messageCount: Int) {
        let harness = FooterGeometryHarness(messageCount: messageCount)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history")
        harness.applyFooterInsert(label: "Working")
        harness.assertPinnedOnComposerLine(step: "footer-insert-Working")
        harness.applyFooterLabelChange(label: "Working…")
        harness.assertPinnedOnComposerLine(step: "footer-label-Working…")
        harness.applyFooterLabelChange(label: "Idle")
        harness.assertPinnedOnComposerLine(step: "footer-label-Idle")
    }

    @Test("footerCollapseIsRemove_expandIsInsert", arguments: [24, 2])
    func footerCollapseIsRemove_expandIsInsert(messageCount: Int) {
        let harness = FooterGeometryHarness(messageCount: messageCount)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history")
        harness.applyFooterInsert(label: "Working")
        harness.assertPinnedOnComposerLine(step: "footer-insert-Working")
        harness.applyFooterRemove()
        harness.assertPinnedOnComposerLine(step: "footer-removed")
        harness.applyFooterInsert(label: "Working")
        harness.assertPinnedOnComposerLine(step: "footer-reinsert-Working")
    }

    @Test("footerIdleDismiss", arguments: [24, 2])
    func footerIdleDismiss(messageCount: Int) {
        let harness = FooterGeometryHarness(messageCount: messageCount)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history")
        harness.applyFooterInsert(label: "Idle")
        harness.assertPinnedOnComposerLine(step: "footer-insert-Idle")
        harness.applyFooterCollapse(label: "Idle")
        harness.assertPinnedOnComposerLine(step: "footer-collapse-Idle")
        harness.applyFooterRemove()
        harness.assertPinnedOnComposerLine(step: "footer-removed")
    }

    @Test("footerInsertWithNewMessage", arguments: [24, 2])
    func footerInsertWithNewMessage(messageCount: Int) {
        let harness = FooterGeometryHarness(messageCount: messageCount)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history")
        harness.applyNewMessageAndFooterInsert()
        harness.assertPinnedOnComposerLine(step: "footer-insert-with-new-message")
    }

    @Test("footerInsertDuringComposerInsetChange", arguments: [24, 2])
    func footerInsertDuringComposerInsetChange(messageCount: Int) {
        let harness = FooterGeometryHarness(messageCount: messageCount)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history")
        harness.controller.updateBottomOverlayInset(96)
        harness.applyFooterInsert(label: "Working", settleAfter: false)
        harness.settle()
        harness.assertPinnedOnComposerLine(step: "footer-insert-composer-inset-96")
    }

    @Test func footerInsertShortThread() {
        let harness = FooterGeometryHarness(messageCount: 2)
        harness.applyHistory()
        harness.assertPinnedOnComposerLine(step: "history-short")
        harness.applyFooterInsert(label: "Working")
        harness.assertPinnedOnComposerLine(step: "footer-insert-Working-short")
    }
}

@MainActor
private final class FooterGeometryHarness {
    let window: UIWindow
    let controller: MessageListTimelineViewController<FooterHarnessItem, FooterHarnessRow>
    let scroll: TimelineScrollController
    let messageCount: Int
    let interItemSpacing: CGFloat

    private var items: [FooterHarnessItem]
    private var frameRevision: UInt64 = 0

    init(messageCount: Int) {
        self.messageCount = messageCount
        self.interItemSpacing = FooterGeometryTestDefaults.interItemSpacing
        items = FooterGeometryHarness.makeMessages(count: messageCount)
        scroll = TimelineScrollController(initialMessageCount: 0)
        controller = MessageListTimelineViewController(
            itemContent: { FooterHarnessRow(item: $0) },
            horizontalContentPadding: 16,
            topContentPadding: 12,
            bottomContentPadding: 0,
            bottomOverlayInset: 120,
            interItemSpacing: FooterGeometryTestDefaults.interItemSpacing
        )
        scroll.attach(controller)
        controller.scrollController = scroll

        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window.windowScene = scene
        }
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        controller.view.layoutIfNeeded()
    }

    func settle() {
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    }

    func applyHistory() {
        apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: nextRevision(),
                insertedRowIDs: Set(items.map(\.id)),
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: Set(items.map(\.id)),
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: true,
                kinds: [.structure]
            )
        )
    }

    func applyFooterInsert(label: String, settleAfter: Bool = true) {
        items.append(footerItem(label: label, collapsed: false))
        apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: nextRevision(),
                insertedRowIDs: [FooterGeometryTestDefaults.workingID],
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: [FooterGeometryTestDefaults.workingID],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            ),
            settleAfter: settleAfter
        )
    }

    func applyFooterLabelChange(label: String) {
        replaceFooter(label: label, collapsed: false)
        apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: nextRevision(),
                insertedRowIDs: [],
                changedRowIDs: [FooterGeometryTestDefaults.workingID],
                removedRowIDs: [],
                layoutChangedRowIDs: [],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.content]
            )
        )
    }

    func applyFooterCollapse(label: String) {
        replaceFooter(label: label, collapsed: true)
        apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: nextRevision(),
                insertedRowIDs: [],
                changedRowIDs: [FooterGeometryTestDefaults.workingID],
                removedRowIDs: [],
                layoutChangedRowIDs: [FooterGeometryTestDefaults.workingID],
                grewBottomRowIDs: [],
                decorateRowIDs: [FooterGeometryTestDefaults.workingID],
                isRequired: false,
                kinds: [.content, .decorate]
            )
        )
    }

    func applyFooterRemove() {
        items.removeAll { $0.id == FooterGeometryTestDefaults.workingID }
        apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: nextRevision(),
                insertedRowIDs: [],
                changedRowIDs: [],
                removedRowIDs: [FooterGeometryTestDefaults.workingID],
                layoutChangedRowIDs: [],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            )
        )
    }

    func applyNewMessageAndFooterInsert() {
        let nextIndex = items.filter { !$0.isFooter }.count
        items.append(
            FooterHarnessItem(
                id: "msg-\(nextIndex)",
                text: Self.messageText(index: nextIndex),
                isFooter: false,
                isCollapsed: false
            )
        )
        items.append(footerItem(label: "Working", collapsed: false))
        apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: nextRevision(),
                insertedRowIDs: ["msg-\(nextIndex)", FooterGeometryTestDefaults.workingID],
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: ["msg-\(nextIndex)", FooterGeometryTestDefaults.workingID],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: true,
                kinds: [.structure]
            )
        )
    }

    func assertPinnedOnComposerLine(step: String) {
        settle()
        #expect(
            controller.testAdjustedContentInsetBottom >= 96,
            "\(geometryMessage(step: step, extra: "adjustedContentInset.bottom must include composer clearance"))"
        )

        let snapshot = captureSnapshot()
        let composerLineY = snapshot.composerLineY
        let offsetDelta = abs(snapshot.contentOffsetY - snapshot.bottomContentOffsetY)
        let offsetPinned = offsetDelta < Self.pinTolerance

        let lastVisible = lastVisibleHeightItem()
        let footer = items.last(where: \.isFooter)
        let footerCollapsed = footer?.isCollapsed == true
        let lastVisibleMaxY = lastVisible.flatMap { controller.testItemFrame(id: $0.id)?.maxY }
        let expectedLastVisibleMaxY =
            composerLineY - (footerCollapsed ? interItemSpacing : 0)
        let lastVisibleAligned: Bool = {
            guard let lastVisibleMaxY else { return false }
            return abs(lastVisibleMaxY - expectedLastVisibleMaxY) < Self.pinTolerance
        }()

        let footerFrame = controller.testItemFrame(id: Self.workingID)
        let collapsedFooterHeightOK: Bool = {
            guard footerCollapsed else { return true }
            return (footerFrame?.height ?? .greatestFiniteMagnitude) < 0.5
        }()

        let overflowingIDs = items.compactMap { item -> String? in
            guard let frame = controller.testItemFrame(id: item.id) else { return nil }
            return frame.maxY > composerLineY + Self.pinTolerance ? item.id : nil
        }
        let nothingUnderComposer = overflowingIDs.isEmpty

        let passed =
            offsetPinned
            && lastVisibleAligned
            && collapsedFooterHeightOK
            && nothingUnderComposer

        let extra = [
            "offsetPinned=\(offsetPinned) Δ=\(fmt(offsetDelta))",
            "lastVisible=\(lastVisible?.id ?? "nil") maxY=\(fmt(lastVisibleMaxY)) expected=\(fmt(expectedLastVisibleMaxY)) aligned=\(lastVisibleAligned)",
            "collapsedHeightOK=\(collapsedFooterHeightOK)",
            "overflow=\(overflowingIDs.joined(separator: ","))",
            "pinnedToEnd=\(scroll.isPinnedToEnd)",
        ].joined(separator: " ")
        let message = geometryMessage(step: step, extra: extra)
        print("FOOTER_GEOM \(message) pass=\(passed)")
        #expect(passed, Comment(rawValue: message))
    }

    private func apply(
        items: [FooterHarnessItem],
        packet: MessageListTimelineApplyPacket,
        settleAfter: Bool = true
    ) {
        self.items = items
        controller.apply(items: items, packet: packet, animatingDifferences: false)
        if settleAfter {
            settle()
        }
    }

    private func replaceFooter(label: String, collapsed: Bool) {
        items.removeAll { $0.isFooter }
        items.append(footerItem(label: label, collapsed: collapsed))
    }

    private func footerItem(label: String, collapsed: Bool) -> FooterHarnessItem {
        FooterHarnessItem(
            id: Self.workingID,
            text: label,
            isFooter: true,
            isCollapsed: collapsed
        )
    }

    private func nextRevision() -> UInt64 {
        frameRevision += 1
        return frameRevision
    }

    private func lastVisibleHeightItem() -> FooterHarnessItem? {
        items.reversed().first { item in
            let height = controller.testItemFrame(id: item.id)?.height ?? 0
            return height >= 0.5
        }
    }

    private func captureSnapshot() -> GeometrySnapshot {
        let footerFrame = controller.testItemFrame(id: Self.workingID)
        let lastMessage = items.last { !$0.isFooter }
        return GeometrySnapshot(
            contentOffsetY: controller.testContentOffsetY,
            bottomContentOffsetY: controller.testBottomContentOffsetY,
            composerLineY: controller.testComposerLineY,
            footerMinY: footerFrame?.minY,
            footerMaxY: footerFrame?.maxY,
            footerHeight: footerFrame?.height,
            lastMessageMaxY: lastMessage.flatMap { controller.testItemFrame(id: $0.id)?.maxY },
            contentHeight: controller.testContentHeight,
            adjustedContentInsetBottom: controller.testAdjustedContentInsetBottom,
            boundsHeight: controller.testBoundsHeight,
            isPinnedToEnd: scroll.isPinnedToEnd
        )
    }

    private func geometryMessage(step: String, extra: String) -> String {
        let snapshot = captureSnapshot()
        return [
            "step=\(step)",
            "thread=\(messageCount)",
            "offset=\(fmt(snapshot.contentOffsetY))",
            "bottom=\(fmt(snapshot.bottomContentOffsetY))",
            "composer=\(fmt(snapshot.composerLineY))",
            "footer=(\(fmt(snapshot.footerMinY))/\(fmt(snapshot.footerMaxY))/\(fmt(snapshot.footerHeight)))",
            "lastMsgMaxY=\(fmt(snapshot.lastMessageMaxY))",
            "contentH=\(fmt(snapshot.contentHeight))",
            "insetB=\(fmt(snapshot.adjustedContentInsetBottom))",
            "boundsH=\(fmt(snapshot.boundsHeight))",
            "pinned=\(snapshot.isPinnedToEnd)",
            extra,
        ].joined(separator: " ")
    }

    private func fmt(_ value: CGFloat?) -> String {
        guard let value else { return "nil" }
        return String(format: "%.2f", value)
    }

    private static let workingID = SessionChatStatusChrome.workingRowID
    private static let pinTolerance: CGFloat = 1.5

    private static func makeMessages(count: Int) -> [FooterHarnessItem] {
        (0..<count).map { index in
            FooterHarnessItem(
                id: "msg-\(index)",
                text: messageText(index: index),
                isFooter: false,
                isCollapsed: false
            )
        }
    }

    private static func messageText(index: Int) -> String {
        "Row \(index) " + String(repeating: "m", count: 40)
    }
}

private struct GeometrySnapshot {
    let contentOffsetY: CGFloat
    let bottomContentOffsetY: CGFloat
    let composerLineY: CGFloat
    let footerMinY: CGFloat?
    let footerMaxY: CGFloat?
    let footerHeight: CGFloat?
    let lastMessageMaxY: CGFloat?
    let contentHeight: CGFloat
    let adjustedContentInsetBottom: CGFloat
    let boundsHeight: CGFloat
    let isPinnedToEnd: Bool
}

private struct FooterHarnessItem: Identifiable, MessageListTimelineHeightEstimating {
    let id: String
    let text: String
    let isFooter: Bool
    let isCollapsed: Bool

    var timelineRowHeightEstimate: CGFloat {
        if isFooter {
            return isCollapsed ? 0 : 64
        }
        return MessageListTimelineRowHeightEstimate.fallback(
            characterCount: text.count,
            style: .assistant
        )
    }
}

private struct FooterHarnessRow: View {
    let item: FooterHarnessItem

    var body: some View {
        if item.isFooter {
            HStack(alignment: .center, spacing: 8) {
                Circle()
                    .frame(width: 6, height: 6)
                Text(item.text)
                    .font(.body)
                Spacer(minLength: 0)
            }
            .opacity(item.isCollapsed ? 0 : 1)
            .padding(.top, item.isCollapsed ? 0 : 12)
            .padding(.bottom, item.isCollapsed ? 0 : 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: item.isCollapsed ? 0 : 36, maxHeight: item.isCollapsed ? 0 : nil)
            .clipped()
        } else {
            HStack {
                Text(item.text)
                    .padding(.vertical, 12)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity)
        }
    }
}
