import CoreGraphics
import Testing
@testable import OpenCode_Mobile

/// The lift now only tracks which row is up and the last target it announced,
/// so each haptic fires exactly once. Travel no longer arbitrates anything:
/// remove moved onto the swipe axis.
struct OpenCodeReorderableListLiftTests {
    @Test func beginLift_startsOnceWhileTheSameRowStaysUp() {
        var lift = OpenCodeReorderableListLift<String>()
        let started = lift.beginLift(id: "a", index: 1)
        let startedAgain = lift.beginLift(id: "a", index: 1)
        let liftedID = lift.liftedID
        #expect(started)
        #expect(!startedAgain)
        #expect(liftedID == "a")
    }

    @Test func beginLift_startsAgainWhenADifferentRowLifts() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        let startedOther = lift.beginLift(id: "b", index: 2)
        let liftedID = lift.liftedID
        let announced = lift.announcedTargetIndex
        #expect(startedOther)
        #expect(liftedID == "b")
        #expect(announced == 2)
    }

    @Test func noteTarget_announcesOnlyOnChange() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        let sameAsLift = lift.noteTarget(1) // already announced by beginLift
        let moved = lift.noteTarget(2)
        let repeated = lift.noteTarget(2)
        let movedBack = lift.noteTarget(1)
        #expect(!sameAsLift)
        #expect(moved)
        #expect(!repeated)
        #expect(movedBack)
    }

    @Test func finish_clearsLiftState() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        lift.finish()
        let liftedID = lift.liftedID
        let announced = lift.announcedTargetIndex
        #expect(liftedID == nil)
        #expect(announced == nil)
    }

    /// A cancelled drag never reaches `onEnded`, so the list releases identity
    /// from the gesture-session reset instead. The same row must then be able
    /// to lift again and re-announce.
    @Test func releaseIdentity_letsTheSameRowStartAFreshLift() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        lift.releaseIdentity()
        let clearedID = lift.liftedID
        let startedAgain = lift.beginLift(id: "a", index: 1)
        #expect(clearedID == nil)
        #expect(startedAgain)
    }
}
