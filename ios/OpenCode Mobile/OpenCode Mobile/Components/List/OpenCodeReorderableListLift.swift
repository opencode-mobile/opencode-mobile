import CoreGraphics

/// Lift-scoped flags for a reorderable row: which row is up, the last
/// announced target, and whether the finger has already left the
/// context-action dead zone.
///
/// A cancelled drag does not run `finish()`, so the list must
/// `releaseIdentity()` when the gesture session returns to inactive.
/// Otherwise the next lift of the same row keeps `travelledPastContextLimit`
/// and skips `onContextAction`.
struct OpenCodeReorderableListLift<ID: Hashable>: Equatable {
    private(set) var liftedID: ID?
    private(set) var announcedTargetIndex: Int?
    private(set) var travelledPastContextLimit = false

    var contextActionEligible: Bool { !travelledPastContextLimit }

    /// Starts a new lift, or no-ops while the same row is still up.
    /// Returns true only for the first event of a lift so the caller can fire
    /// the lift haptic once.
    mutating func beginLift(id: ID, index: Int) -> Bool {
        guard liftedID != id else { return false }
        liftedID = id
        announcedTargetIndex = index
        travelledPastContextLimit = false
        return true
    }

    mutating func noteTravel(_ translation: CGFloat, movable: Bool) {
        if movable, abs(translation) >= OpenCodeReorderableListMath.contextActionTranslationLimit {
            travelledPastContextLimit = true
        }
    }

    mutating func noteTarget(_ index: Int) -> Bool {
        guard announcedTargetIndex != index else { return false }
        announcedTargetIndex = index
        return true
    }

    /// Release path: reports whether the context action is still allowed, then
    /// clears lift-scoped state.
    mutating func finish() -> Bool {
        let eligible = contextActionEligible
        reset()
        return eligible
    }

    /// Cancel path: drop which row is up so the next lift of the same row is a
    /// new lift. Keep the travel flag until `beginLift` or `finish` so a
    /// GestureState reset that races `onEnded` cannot offer remove after a
    /// real drag.
    mutating func releaseIdentity() {
        liftedID = nil
        announcedTargetIndex = nil
    }

    mutating func reset() {
        releaseIdentity()
        travelledPastContextLimit = false
    }
}
