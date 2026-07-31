import Foundation
import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct MessageListTimelineHostDeclaredIDTests {
    @Test func declaredApply_reconfiguresOnlyDeclaredIDs_withoutItemEquatable() {
        EqualityCountingItem.equalityCallCount = 0

        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)

        let initial = [
            EqualityCountingItem(id: "a", label: "A1"),
            EqualityCountingItem(id: "b", label: "B1"),
            EqualityCountingItem(id: "c", label: "C1"),
        ]
        controller.apply(
            items: initial,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 1,
                insertedRowIDs: ["a", "b", "c"],
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: ["a", "b", "c"],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            ),
            animatingDifferences: false
        )
        EqualityCountingItem.equalityCallCount = 0

        let updated = [
            EqualityCountingItem(id: "a", label: "A1"),
            EqualityCountingItem(id: "b", label: "B2"),
            EqualityCountingItem(id: "c", label: "C1"),
        ]
        controller.apply(
            items: updated,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 2,
                insertedRowIDs: [],
                changedRowIDs: ["b"],
                removedRowIDs: [],
                layoutChangedRowIDs: [],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.content]
            ),
            animatingDifferences: false
        )

        #expect(controller.lastApplyUsedDeclaredIDs)
        #expect(controller.lastReconfiguredIDs == ["b"])
        #expect(EqualityCountingItem.equalityCallCount == 0)
    }

    @Test func sameFrameRevision_skipsReapplyEvenWithLeftoverChangeSets() {
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)

        let items = [
            EqualityCountingItem(id: "a", label: "A"),
            EqualityCountingItem(id: "b", label: "B"),
        ]
        let packet = MessageListTimelineApplyPacket(
            frameRevision: 7,
            insertedRowIDs: ["a", "b"],
            changedRowIDs: [],
            removedRowIDs: [],
            layoutChangedRowIDs: ["a", "b"],
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: false,
            kinds: [.structure]
        )
        controller.apply(items: items, packet: packet, animatingDifferences: false)
        #expect(controller.lastReconfiguredIDs.isEmpty || controller.lastApplyUsedDeclaredIDs)

        controller.apply(items: items, packet: packet, animatingDifferences: false)
        #expect(controller.lastReconfiguredIDs.isEmpty)
    }

    @Test func requiredPacket_appliesImmediatelyWhileDragging() {
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)

        let initial = [EqualityCountingItem(id: "a", label: "A1")]
        controller.apply(
            items: initial,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 1,
                insertedRowIDs: ["a"],
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: ["a"],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: false,
                kinds: [.structure]
            ),
            animatingDifferences: false
        )

        // Simulate an active user scroll without requiring a UIKit gesture.
        let scroll = TimelineScrollController()
        controller.scrollController = scroll
        scroll.beginUserScrolling()

        let requiredItems = [EqualityCountingItem(id: "a", label: "A2")]
        controller.apply(
            items: requiredItems,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 2,
                insertedRowIDs: [],
                changedRowIDs: ["a"],
                removedRowIDs: [],
                layoutChangedRowIDs: [],
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: true,
                kinds: [.required, .content]
            ),
            animatingDifferences: false
        )

        #expect(controller.lastReconfiguredIDs == ["a"])
        #expect(controller.lastApplyUsedDeclaredIDs)
    }
}

/// Identifiable test row that counts Equatable calls — host must not use them.
private struct EqualityCountingItem: Identifiable, Equatable {
    let id: String
    let label: String
    static var equalityCallCount = 0

    static func == (lhs: EqualityCountingItem, rhs: EqualityCountingItem) -> Bool {
        equalityCallCount += 1
        return lhs.id == rhs.id && lhs.label == rhs.label
    }
}
