import CoreGraphics
import Testing
@testable import OpenCode_Mobile

struct OpenCodeReorderableListLiftTests {
    private let limit = OpenCodeReorderableListMath.contextActionTranslationLimit

    @Test func finish_offersContextActionWhenFingerStayedPut() {
        var lift = OpenCodeReorderableListLift<String>()
        let started = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(limit - 1, movable: true)
        let eligible = lift.finish()
        #expect(started)
        #expect(eligible)
        #expect(lift.liftedID == nil)
    }

    @Test func finish_skipsContextActionAfterTravelEvenIfFingerReturns() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(limit, movable: true)
        lift.noteTravel(0, movable: true)
        let eligible = lift.finish()
        #expect(!eligible)
    }

    @Test func releaseIdentity_letsTheSameRowStartAFreshLift() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(limit, movable: true)
        lift.releaseIdentity()

        #expect(lift.travelledPastContextLimit)
        let startedAgain = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(0, movable: true)
        let eligible = lift.finish()
        #expect(startedAgain)
        #expect(eligible)
    }

    @Test func finish_stillSeesTravelAfterIdentityWasReleased() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(limit, movable: true)
        lift.releaseIdentity()
        let eligible = lift.finish()
        #expect(!eligible)
    }

    @Test func beginLift_doesNotResetTravelWhileTheSameRowIsStillUp() {
        var lift = OpenCodeReorderableListLift<String>()
        let started = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(limit, movable: true)
        let startedAgain = lift.beginLift(id: "a", index: 1)
        #expect(started)
        #expect(!startedAgain)
        #expect(lift.travelledPastContextLimit)
    }

    @Test func beginLift_resetsTravelWhenADifferentRowLifts() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 1)
        lift.noteTravel(limit, movable: true)
        let startedOther = lift.beginLift(id: "b", index: 2)
        let eligible = lift.finish()
        #expect(startedOther)
        #expect(!lift.travelledPastContextLimit)
        #expect(eligible)
    }

    @Test func noteTravel_ignoresImmovableRows() {
        var lift = OpenCodeReorderableListLift<String>()
        _ = lift.beginLift(id: "a", index: 0)
        lift.noteTravel(limit, movable: false)
        let eligible = lift.finish()
        #expect(eligible)
    }
}
