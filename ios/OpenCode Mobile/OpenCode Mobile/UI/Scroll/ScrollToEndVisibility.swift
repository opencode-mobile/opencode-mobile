import CoreGraphics

enum ScrollToEndVisibility {
    private static let minRevealThreshold: CGFloat = 120
    private static let viewportRevealFraction: CGFloat = 0.2
    /// Once shown, the pill stays until the reader is this much closer than the reveal threshold,
    /// so the pills that follow it (Syncing, the folded activity pill) do not flap near the edge.
    static let hideMargin: CGFloat = 32

    static func revealThreshold(viewportHeight: CGFloat) -> CGFloat {
        max(minRevealThreshold, viewportHeight * viewportRevealFraction)
    }

    static func shouldShow(
        scrollDistanceFromBottom: CGFloat,
        viewportHeight: CGFloat,
        isShowing: Bool = false
    ) -> Bool {
        let threshold = revealThreshold(viewportHeight: viewportHeight)
        return scrollDistanceFromBottom > (isShowing ? threshold - hideMargin : threshold)
    }
}
