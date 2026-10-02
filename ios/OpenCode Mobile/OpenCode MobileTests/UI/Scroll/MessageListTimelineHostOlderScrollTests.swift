import Foundation
import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct MessageListTimelineHostOlderScrollTests {
    /// B5-3: pulled past the oldest row when the older page lands. The scroll
    /// view is still animating back to the old top edge; the restore must end
    /// that motion and keep the old first row where it was, instead of letting
    /// it slide the newly filled rows (the "moves back a turn" symptom).
    @Test func prependWhileOverscrolledAtTopKeepsOldFirstRowInPlace() async throws {
        let controller = MessageListTimelineViewController<OlderScrollItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        let initialItems = (20...40).map { index in
            OlderScrollItem(id: "m\(index)", label: String(repeating: "Row \(index) ", count: 12))
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
                isRequired: true,
                kinds: [.structure, .required]
            ),
            animatingDifferences: false
        )
        // Diffable completions run on a later main-queue turn; a nested RunLoop
        // run inside this MainActor test cannot drain them, so suspend instead.
        try await settle(controller)
        // Pull 80pt past the oldest loaded row; layout starts the snap back.
        controller.testSetContentOffsetY(-controller.testAdjustedContentInsetTop - 80)
        controller.view.layoutIfNeeded()
        scroll.beginUserScrolling()
        scroll.endUserScrolling()
        let anchorBefore = try #require(controller.testItemFrame(id: "m20"))

        // Short older rows: real heights far below the 120pt layout estimate.
        let olderItems = (1...19).map { OlderScrollItem(id: "m\($0)", label: "Older \($0)") }
        controller.apply(
            items: olderItems + initialItems,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 2,
                insertedRowIDs: Set(olderItems.map(\.id)),
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: Set(olderItems.map(\.id)),
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: true,
                kinds: [.structure, .required]
            ),
            animatingDifferences: false
        )
        try await settle(controller)

        let anchorAfter = try #require(controller.testItemFrame(id: "m20"))
        #expect(
            abs(anchorAfter.minY - anchorBefore.minY) < 2,
            "anchor moved from \(anchorBefore.minY) to \(anchorAfter.minY)"
        )
        #expect(!scroll.isProgrammaticScrolling)
    }

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

    @Test func nearOldestDoesNotFireOnInitialApply() {
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
        #expect(fires == 0)
    }

    @Test func nearOldestVisibleFiresOncePerUserScrollTowardOldest() {
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

        controller.testArmUserScrollTowardOldest()
        controller.testNotifyNearOldestIfNeeded()
        #expect(fires == 1)

        controller.testNotifyNearOldestIfNeeded()
        #expect(fires == 1)

        controller.testArmUserScrollTowardOldest()
        controller.testNotifyNearOldestIfNeeded()
        #expect(fires == 2)
    }
}

@MainActor
private func settle(
    _ controller: MessageListTimelineViewController<OlderScrollItem, Text>
) async throws {
    for _ in 0..<3 {
        controller.view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(40))
    }
    controller.view.layoutIfNeeded()
}

private struct OlderScrollItem: Identifiable {
    let id: String
    let label: String
}
