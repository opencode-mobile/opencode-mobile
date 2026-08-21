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
            bottomOverlayInset: 0,
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
            bottomOverlayInset: 0,
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

    @Test func statusChromeLabelChange_reconfiguresStableStatusRow() {
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)

        // Empty declared sets: only status-chrome folding should drive reconfigure
        // when the Working label changes under a stable timeline revision.
        let frame = TimelineFrame(
            frameRevision: 3,
            rows: [],
            insertedRowIDs: [],
            changedRowIDs: [],
            removedRowIDs: [],
            layoutChangedRowIDs: [],
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: false
        )
        let workingID = SessionChatStatusChrome.workingRowID
        let initialItems = [
            EqualityCountingItem(id: "a", label: "A"),
            EqualityCountingItem(id: workingID, label: "Working"),
        ]
        let initialPacket = MessageListTimelineApplyPacket.from(
            frame,
            workingIndicatorLabel: "Working",
            previousWorkingIndicatorLabel: nil
        )
        controller.apply(items: initialItems, packet: initialPacket, animatingDifferences: false)
        #expect(controller.diffableSnapshotApplyCount == 1)

        let updatedItems = [
            EqualityCountingItem(id: "a", label: "A"),
            EqualityCountingItem(id: workingID, label: "Compacting"),
        ]
        let updatedPacket = MessageListTimelineApplyPacket.from(
            frame,
            workingIndicatorLabel: "Compacting",
            previousWorkingIndicatorLabel: "Working"
        )
        #expect(updatedPacket.frameRevision != initialPacket.frameRevision)
        #expect(updatedPacket.changedRowIDs.contains(workingID))
        #expect(!updatedPacket.insertedRowIDs.contains(workingID))

        controller.apply(items: updatedItems, packet: updatedPacket, animatingDifferences: false)
        #expect(controller.diffableSnapshotApplyCount == 2)
        #expect(controller.lastReconfiguredIDs == [workingID])

        // Same chrome + same timeline frame must not storm.
        controller.apply(items: updatedItems, packet: updatedPacket, animatingDifferences: false)
        #expect(controller.diffableSnapshotApplyCount == 2)
    }

    @Test func statusChromeUnchangedLabel_doesNotReconfigureWorkingRowWhenTimelineAdvances() {
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)

        let workingID = SessionChatStatusChrome.workingRowID
        let items = [
            EqualityCountingItem(id: "a", label: "A"),
            EqualityCountingItem(id: workingID, label: "Working"),
        ]
        let initialFrame = TimelineFrame(
            frameRevision: 3,
            rows: [],
            insertedRowIDs: ["a", workingID],
            changedRowIDs: [],
            removedRowIDs: [],
            layoutChangedRowIDs: ["a", workingID],
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: false
        )
        controller.apply(
            items: items,
            packet: .from(
                initialFrame,
                workingIndicatorLabel: "Working",
                previousWorkingIndicatorLabel: nil
            ),
            animatingDifferences: false
        )

        let advancedFrame = TimelineFrame(
            frameRevision: 4,
            rows: [],
            insertedRowIDs: [],
            changedRowIDs: ["a"],
            removedRowIDs: [],
            layoutChangedRowIDs: [],
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: false
        )
        let advancedPacket = MessageListTimelineApplyPacket.from(
            advancedFrame,
            workingIndicatorLabel: "Working",
            previousWorkingIndicatorLabel: "Working"
        )
        #expect(!advancedPacket.changedRowIDs.contains(workingID))
        #expect(!advancedPacket.insertedRowIDs.contains(workingID))
        #expect(advancedPacket.changedRowIDs.contains("a"))

        controller.apply(items: items, packet: advancedPacket, animatingDifferences: false)
        #expect(controller.lastReconfiguredIDs == ["a"])
    }

    @Test func nilPacket_identityStable_doesNotReconfigureAllOnRepeatApply() {
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)

        let items = [
            EqualityCountingItem(id: "a", label: "A"),
            EqualityCountingItem(id: "b", label: "B"),
        ]
        controller.apply(items: items, packet: nil, animatingDifferences: false)
        #expect(!controller.lastApplyUsedDeclaredIDs)
        #expect(controller.diffableSnapshotApplyCount == 1)

        // Same IDs + nil packet must not synthesize a bumped reconfigure-all
        // (Session Chat freeze: status chrome cleared the declared packet).
        controller.apply(items: items, packet: nil, animatingDifferences: false)
        #expect(controller.diffableSnapshotApplyCount == 1)
        #expect(controller.lastReconfiguredIDs.isEmpty)
        #expect(!controller.lastApplyUsedDeclaredIDs)

        controller.apply(
            items: items + [EqualityCountingItem(id: "c", label: "C")],
            packet: nil,
            animatingDifferences: false
        )
        #expect(controller.diffableSnapshotApplyCount == 2)
        #expect(controller.lastReconfiguredIDs == ["a", "b"])
    }

    @Test func requiredPacket_appliesImmediatelyWhileDragging() {
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
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

    @Test func unpinnedOffsetSurvivesNavigationCover() {
        let scroll = TimelineScrollController(initialMessageCount: 0)
        let controller = MessageListTimelineViewController<EqualityCountingItem, Text>(
            itemContent: { Text($0.label) },
            horizontalContentPadding: 0,
            topContentPadding: 0,
            bottomContentPadding: 0,
            bottomOverlayInset: 0,
            interItemSpacing: 0
        )
        controller.scrollController = scroll
        scroll.attach(controller)
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 400)

        let items = (0..<24).map { index in
            EqualityCountingItem(id: "row-\(index)", label: "Row \(index) " + String(repeating: "m", count: 48))
        }
        controller.apply(
            items: items,
            packet: MessageListTimelineApplyPacket(
                frameRevision: 1,
                insertedRowIDs: Set(items.map(\.id)),
                changedRowIDs: [],
                removedRowIDs: [],
                layoutChangedRowIDs: Set(items.map(\.id)),
                grewBottomRowIDs: [],
                decorateRowIDs: [],
                isRequired: true,
                kinds: [.structure, .required]
            ),
            animatingDifferences: false
        )
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        controller.view.layoutIfNeeded()

        let bottomY = controller.testBottomContentOffsetY
        #expect(bottomY > 120)
        guard bottomY > 120 else { return }

        let midY = bottomY / 2
        controller.testSetContentOffsetY(midY)
        scroll.beginUserScrolling()
        scroll.endUserScrolling()
        #expect(!scroll.isPinnedToEnd)

        let preservedY = controller.testContentOffsetY
        controller.viewWillDisappear(false)
        controller.testSetContentOffsetY(controller.testBottomContentOffsetY)
        controller.viewWillAppear(false)
        controller.view.layoutIfNeeded()

        #expect(abs(controller.testContentOffsetY - preservedY) < 2)
        #expect(!scroll.isPinnedToEnd)
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
