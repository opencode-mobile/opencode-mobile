import CoreGraphics

/// Lift-scoped state for a reorderable row: which row is up and the last
/// announced target, so the caller fires each haptic exactly once.
///
/// A cancelled drag does not run `finish()`, so the list must
/// `releaseIdentity()` when the gesture session returns to inactive. Otherwise
/// the next lift of the same row is not recognised as a new lift and never
/// re-announces its haptic.
struct OpenCodeReorderableListLift<ID: Hashable>: Equatable {
    private(set) var liftedID: ID?
    private(set) var announcedTargetIndex: Int?

    /// Starts a new lift, or no-ops while the same row is still up.
    /// Returns true only for the first event of a lift so the caller can fire
    /// the lift haptic once.
    mutating func beginLift(id: ID, index: Int) -> Bool {
        guard liftedID != id else { return false }
        liftedID = id
        announcedTargetIndex = index
        return true
    }

    mutating func noteTarget(_ index: Int) -> Bool {
        guard announcedTargetIndex != index else { return false }
        announcedTargetIndex = index
        return true
    }

    /// Release path: clears lift-scoped state.
    mutating func finish() {
        releaseIdentity()
    }

    /// Cancel path: drop which row is up so the next lift of the same row is a
    /// new lift.
    mutating func releaseIdentity() {
        liftedID = nil
        announcedTargetIndex = nil
    }
}
