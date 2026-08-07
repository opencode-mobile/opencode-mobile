import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionInfoStatusTests {
    @Test func applyStatus_markOptimistic_whenUnresolvedIdle() {
        var state = SessionInfoRegionState()
        state.applyStatus(authoritative: nil, optimisticBusy: true)

        #expect(state.optimisticBusy)
        #expect(state.sessionStatusLabel == "Working")
        #expect(state.authoritativeStatusLabel == nil)
    }

    @Test func applyStatus_markOptimistic_whenAlreadyResolvedIdle() {
        var state = SessionInfoRegionState()
        state.applyStatus(authoritative: nil, hasResolved: true)
        state.applyStatus(authoritative: nil, optimisticBusy: true)

        #expect(state.optimisticBusy)
        #expect(state.sessionStatusLabel == "Working")
        #expect(state.hasResolvedSessionStatus)
    }

    @Test func applyStatus_authoritativeBusy_clearsOptimistic() {
        var state = SessionInfoRegionState()
        state.applyStatus(authoritative: nil, optimisticBusy: true)
        state.applyStatus(authoritative: "Working", hasResolved: true)

        #expect(!state.optimisticBusy)
        #expect(state.sessionStatusLabel == "Working")
        #expect(state.authoritativeStatusLabel == "Working")
    }

    @Test func applyStatus_resolvedIdle_clearsOptimistic() {
        var state = SessionInfoRegionState()
        state.applyStatus(authoritative: nil, optimisticBusy: true)
        state.applyStatus(authoritative: nil, hasResolved: true)

        #expect(!state.optimisticBusy)
        #expect(state.sessionStatusLabel == nil)
        #expect(state.hasResolvedSessionStatus)
    }

    @Test func applyStatus_unresolvedNull_keepsOptimistic() {
        var state = SessionInfoRegionState()
        state.applyStatus(authoritative: nil, optimisticBusy: true)
        state.applyStatus(authoritative: nil)

        #expect(state.optimisticBusy)
        #expect(state.sessionStatusLabel == "Working")
        #expect(!state.hasResolvedSessionStatus)
    }
}
