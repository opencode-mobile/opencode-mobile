import CoreGraphics

/// What a following sheet body should do after its content changes size.
enum OpenCodeBottomSheetFollowAction: Equatable {
    case none
    /// Scroll to the end of the body.
    case bottom
    /// Scroll the target row back into view; the body has not moved since it was asked for.
    case target
}

/// Whether a sheet body keeps its newest rows in view (#879). A body that
/// follows stays at its bottom as it grows while the reader is parked there;
/// scrolling away stops it and coming back resumes it. A scroll target on a
/// pane wins: the body only follows again once the reader is back at the bottom.
///
/// Mirrors the Android `BottomSheetFollow`.
struct OpenCodeBottomSheetFollow: Equatable {
    /// How close to the end of the body still counts as parked at the bottom.
    static let parkedThreshold: CGFloat = 24

    private(set) var parked = false
    private var targetPending = false

    /// A new pane: start at its bottom when it follows, unless it scrolls to a target.
    mutating func paneChanged(follows: Bool, hasTarget: Bool) {
        targetPending = hasTarget
        parked = follows && !hasTarget
    }

    func contentChanged(follows: Bool) -> OpenCodeBottomSheetFollowAction {
        if targetPending { return .target }
        if follows && parked { return .bottom }
        return .none
    }

    /// The body moved (the reader, or a scroll this asked for);
    /// `distanceFromBottom` is how much of it is left below the viewport.
    mutating func offsetChanged(distanceFromBottom: CGFloat, threshold: CGFloat = parkedThreshold) {
        targetPending = false
        parked = distanceFromBottom <= threshold
    }
}
