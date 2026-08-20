import CoreGraphics
import Foundation

enum SessionListScopePaging {
    static let commitDistance: CGFloat = 36
    static let flickVelocity: CGFloat = 800
    static let settleDuration: TimeInterval = 0.22
    static let listCrossfadeDuration: TimeInterval = 0.16

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

    static func displayedTranslation(
        translation: CGFloat,
        pageWidth: CGFloat,
        canGoPrevious: Bool,
        canGoNext: Bool
    ) -> CGFloat {
        if translation > 0, !canGoPrevious {
            return rubberBand(translation, dimension: max(pageWidth, 1))
        }
        if translation < 0, !canGoNext {
            return -rubberBand(-translation, dimension: max(pageWidth, 1))
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

    private static func rubberBand(_ offset: CGFloat, dimension: CGFloat) -> CGFloat {
        let constant: CGFloat = 0.55
        return (1 - 1 / (offset * constant / dimension + 1)) * dimension
    }
}
