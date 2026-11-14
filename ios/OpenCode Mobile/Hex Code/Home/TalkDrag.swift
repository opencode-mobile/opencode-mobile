import CoreGraphics

/// Dragging sideways while holding the talk button (app plan, "Dictation"): right locks the take,
/// left drops it. Arming is decided on the real finger with hysteresis; the look follows a
/// smoothed copy of the finger through `progress(_:)`.
enum TalkDrag {
    /// Nothing shows in the first 12 pt either way.
    static let deadZone: CGFloat = 12
    /// The finger arms this far from where it touched down…
    static let armDistance: CGFloat = 64
    /// …and disarms only when it comes back below this.
    static let disarmDistance: CGFloat = 52

    enum Arm: Equatable, Sendable {
        /// Release locks the take.
        case lock
        /// Release drops the take.
        case drop
    }

    /// How far the look has gone, −1 (drop) … 1 (lock), for a horizontal offset in points: 0 inside
    /// the dead zone, full at the arm distance.
    static func progress(_ offset: CGFloat) -> CGFloat {
        let reach = max(0, abs(offset) - deadZone) / (armDistance - deadZone)
        return (offset < 0 ? -1 : 1) * min(1, reach)
    }

    /// The arm after the finger moved to `offset`: armed at 64 pt from the touch, disarmed only
    /// below 52 pt (or past the other side's 64 pt).
    static func arm(_ current: Arm?, offset: CGFloat) -> Arm? {
        switch current {
        case .lock?:
            if offset <= -armDistance { return .drop }
            return offset < disarmDistance ? nil : .lock
        case .drop?:
            if offset >= armDistance { return .lock }
            return offset > -disarmDistance ? nil : .drop
        case nil:
            if offset >= armDistance { return .lock }
            if offset <= -armDistance { return .drop }
            return nil
        }
    }
}
