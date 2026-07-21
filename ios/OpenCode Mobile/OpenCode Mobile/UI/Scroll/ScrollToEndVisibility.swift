import CoreGraphics

enum ScrollToEndVisibility {
    private static let minRevealThreshold: CGFloat = 120
    private static let viewportRevealFraction: CGFloat = 0.2

    static func revealThreshold(viewportHeight: CGFloat) -> CGFloat {
        max(minRevealThreshold, viewportHeight * viewportRevealFraction)
    }

    static func shouldShow(
        scrollDistanceFromBottom: CGFloat,
        viewportHeight: CGFloat,
        isUserScrolling: Bool
    ) -> Bool {
        guard !isUserScrolling else { return false }

        return scrollDistanceFromBottom > revealThreshold(viewportHeight: viewportHeight)
    }
}
