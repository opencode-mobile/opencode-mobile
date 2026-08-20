import CoreGraphics
import Foundation

enum SessionListScopePaging {
    static let switchThreshold: CGFloat = 28
    static let switchDuration: TimeInterval = 0.45
    static let listCrossfadeDuration: TimeInterval = 0.16
    /// Max visual travel when rubber-banding.
    static let rubberBandLimit: CGFloat = 52
    static let rubberBandConstant: CGFloat = 0.50
    static let rubberBandReturnDuration: TimeInterval = 0.18

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
