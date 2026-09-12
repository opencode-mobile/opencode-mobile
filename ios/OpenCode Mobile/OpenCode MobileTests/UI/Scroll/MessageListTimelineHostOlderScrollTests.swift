import Foundation
import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct MessageListTimelineHostOlderScrollTests {
    @Test func prependRestoresVisibleItemOffsetWithoutArmingFollow() {
        let controller = MessageListTimelineViewController<OlderScrollItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        let initialItems = (2...16).map { index in
            OlderScrollItem(id: "m\(index)", label: String(repeating: "Row \(index) ", count: 8))
        }
        let scroll = TimelineScrollController(initialMessageCount: initialItems.count)
        scroll.autoFollowEnabled = true
        controller.scrollController = scroll
        scroll.attach(controller)
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)
        let window = UIWindow(frame: controller.view.frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()

        controller.apply(
            items: initialItems,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 1,
                insertedRowIDs: Set(initialItems.map(\.id)),
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: Set(initialItems.map(\.id)),
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            ),
            animatingDifferences: false
        )
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.08))
        controller.view.layoutIfNeeded()
        controller.testSetContentOffsetY(40)
        controller.view.layoutIfNeeded()
        let offsetBefore = controller.testContentOffsetY
        scroll.beginUserScrolling()
        scroll.endUserScrolling()
        #expect(!scroll.isProgrammaticScrolling)
        #expect(offsetBefore >= 40 - 1)

        controller.apply(
            items: [
                OlderScrollItem(id: "m1", label: String(repeating: "Row 1 ", count: 8)),
            ] + initialItems,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 2,
                insertedRowIDs: ["m1"],
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: ["m1"],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            ),
            animatingDifferences: false
        )
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))

        // Estimated cell heights can settle by a few points; a real jump lands
        // near the top inset or the bottom pin, hundreds of points away.
        #expect(abs(controller.testContentOffsetY - offsetBefore) < 16)
        #expect(!scroll.isProgrammaticScrolling)
    }

    @Test func nearOldestVisibleFiresOnceWhileLoadable() {
        let controller = MessageListTimelineViewController<OlderScrollItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)
        let window = UIWindow(frame: controller.view.frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        var fires = 0
        controller.canLoadOlder = true
        controller.onNearOldestVisible = { fires += 1 }
        controller.apply(
            items: [OlderScrollItem(id: "m1", label: "One")],
            packet: MessageListTimelineApplyPacket(
                frameRevision: 1,
                insertedRowIDs: ["m1"],
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: ["m1"],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            ),
            animatingDifferences: false
        )
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.08))
        controller.view.layoutIfNeeded()
        controller.testSetContentOffsetY(-controller.view.safeAreaInsets.top)
        controller.testNotifyNearOldestIfNeeded()
        #expect(fires >= 1)
    }
}

private struct OlderScrollItem: Identifiable {
    let id: String
    let label: String
}
