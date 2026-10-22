import CoreGraphics
import Foundation

enum SessionListScopePaging {
    static let switchThreshold: CGFloat = 28
    static let switchDuration: TimeInterval = 0.45
    static let listCrossfadeDuration: TimeInterval = 0.40
    /// Max visual travel when rubber-banding.
    static let rubberBandLimit: CGFloat = 52
    static let rubberBandConstant: CGFloat = 0.50
    static let rubberBandReturnDuration: TimeInterval = 0.18
    static let edgeFadeWidth: CGFloat = 16
    static let edgeFadeDuration: TimeInterval = 0.20

    enum Direction: Equatable {
        case previous
        case next
    }

    static func neighborIndex(selectedIndex: Int, count: Int, direction: Direction) -> Int? {
        switch direction {
        case .previous:
            let index = selectedIndex - 1
            return index >= 0 ? index : nil
        case .next:
            let index = selectedIndex + 1
            return index < count ? index : nil
        }
    }

    /// Neighbor of the selection in `tabIDs`. A selection that is not a tab itself (a worktree
    /// scope) sits inside its `parentID` tab: previous goes to that tab, next to the one after it.
    static func neighborID(
        selectedID: String,
        parentID: String?,
        tabIDs: [String],
        direction: Direction
    ) -> String? {
        if let index = tabIDs.firstIndex(of: selectedID) {
            return neighborIndex(selectedIndex: index, count: tabIDs.count, direction: direction).map { tabIDs[$0] }
        }
        guard let parentID, let parentIndex = tabIDs.firstIndex(of: parentID) else { return nil }
        switch direction {
        case .previous:
            return tabIDs[parentIndex]
        case .next:
            return neighborIndex(selectedIndex: parentIndex, count: tabIDs.count, direction: .next).map { tabIDs[$0] }
        }
    }

    /// Visual offset while dragging: resists in both directions, never pages.
    static func rubberBandOffset(_ translation: CGFloat) -> CGFloat {
        if translation > 0 { return rubberBand(translation) }
        if translation < 0 { return -rubberBand(-translation) }
        return 0
    }

    /// Finger-up commit from rubber-band visual offset. No flick; no wrap.
    static func commitDirection(
        visualOffset: CGFloat,
        canGoPrevious: Bool,
        canGoNext: Bool
    ) -> Direction? {
        if canGoNext, visualOffset <= -switchThreshold {
            return .next
        }
        if canGoPrevious, visualOffset >= switchThreshold {
            return .previous
        }
        return nil
    }

    /// Offset that places a cluster fully out of the slot for the canned switch.
    static func settleTranslation(direction: Direction?, pageWidth: CGFloat) -> CGFloat {
        let width = max(pageWidth, 1)
        switch direction {
        case .next: return -width
        case .previous: return width
        case nil: return 0
        }
    }

    private static func rubberBand(_ offset: CGFloat) -> CGFloat {
        let dimension = rubberBandLimit
        let constant = rubberBandConstant
        return (1 - 1 / (offset * constant / dimension + 1)) * dimension
    }
}
