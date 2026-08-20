import CoreGraphics
import Foundation

enum SessionListScopePaging {
    static let commitDistance: CGFloat = 36
    static let flickVelocity: CGFloat = 800
    static let settleDuration: TimeInterval = 0.40
    static let listCrossfadeDuration: TimeInterval = 0.16
    /// Max visual travel when there is nothing to page to.
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

    static func displayedTranslation(
        translation: CGFloat,
        pageWidth: CGFloat,
        canGoPrevious: Bool,
        canGoNext: Bool,
        resistOverscroll: Bool
    ) -> CGFloat {
        if resistOverscroll {
            if translation > 0, !canGoPrevious {
                return rubberBand(translation)
            }
            if translation < 0, !canGoNext {
                return -rubberBand(-translation)
            }
        }
        if canGoPrevious || canGoNext {
            return min(max(translation, -pageWidth), pageWidth)
        }
        return translation
    }

    static func commitDirection(
        translation: CGFloat,
        velocity: CGFloat,
        canGoPrevious: Bool,
        canGoNext: Bool
    ) -> Direction? {
        if canGoNext, translation <= -commitDistance || velocity <= -flickVelocity {
            return .next
        }
        if canGoPrevious, translation >= commitDistance || velocity >= flickVelocity {
            return .previous
        }
        return nil
    }

    /// Offset that places the committed neighbor fully in the slot, before selection swaps.
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
