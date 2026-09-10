import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct SseRecoveryFaultOracleTests {
    @Test func loaderDecodesAllSharedTraces() throws {
        let traces = try SseFaultOracleLoader.loadAllTraces()
        #expect(traces.map(\.id) == SseFaultOracleLoader.traceIDs)
        for trace in traces {
            #expect(!trace.steps.isEmpty)
            #expect(!trace.expected.snapshots.isEmpty)
            #expect(!trace.seed.profileId.isEmpty)
            #expect(!trace.seed.sessionId.isEmpty)
        }
    }

    @Test func loaderSchemaDeclaresTraceAndExpectedContracts() throws {
        let traceSchema = try SseFaultOracleLoader.loadSchemaObject("trace.schema.json")
        let expectedSchema = try SseFaultOracleLoader.loadSchemaObject("expected.schema.json")
        #expect(
            SseFaultOracleLoader.schemaRequiredFields(traceSchema) ==
                ["id", "surface", "protocol", "seed", "steps", "expected"]
        )
        #expect(SseFaultOracleLoader.schemaRequiredFields(expectedSchema) == ["snapshots"])
        #expect(traceSchema["$defs"] != nil)
        #expect(expectedSchema["$defs"] != nil)
    }

    @Test func briefBackgroundSameEpochDoesNotLatchStale() async throws {
        try await SseFaultOracleDriver.run(
            SseFaultOracleLoader.loadTrace("android-brief-background-same-epoch-stale-latch")
        )
    }

    @Test func coldResumeDoubleEpochPerformsOneCatchUp() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("cold-resume-double-epoch"))
    }

    @Test func profileSwitchRetargetsStreamAndDropsOldPublication() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("ios-profile-a-to-b-old-stream"))
    }

    @Test func v1SilentInstanceIsNotKeptLiveByGlobalHeartbeats() async throws {
        try await SseFaultOracleDriver.run(
            SseFaultOracleLoader.loadTrace("v1-one-target-silent-while-other-heartbeats")
        )
    }

    @Test func addProjectWhileV1LiveOpensNewInstanceStream() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("add-project-while-v1-live"))
    }

    @Test func v1ToV2ProbeWhileOccupyingRetargetsRequiredTargets() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("v1-to-v2-probe-while-occupying"))
    }

    @Test func removeProjectWhileV1LiveCancelsOnlyRemovedLease() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("remove-project-while-v1-live"))
    }

    @Test func gapDuringInitialRestRequiresSecondReconciliation() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("gap-during-initial-rest"))
    }

    @Test func profileAToBToADoesNotPublishCrossProfileEvents() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("profile-a-to-b-to-a"))
    }

    @Test func restSlowSseWinsDoesNotOverwriteLivePatch() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("rest-slow-sse-wins"))
    }

    @Test func restFailRetriesSameGapStaysUnsyncedUntilSuccess() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("rest-fail-retries-same-gap"))
    }

    @Test func lateSubscribeWithOpenGapReconcilesOnce() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("late-subscribe-open-gap"))
    }

    @Test func clearCacheDropsOldObservationAndReconciles() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("clear-cache-drops-old-observation"))
    }

    @Test func parseFailureOpensOneGapAndReconciles() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("sse-parse-failed-opens-gap"))
    }

    @Test func v1InstanceLeaseLostRecoversWithOneReopen() async throws {
        try await SseFaultOracleDriver.run(
            SseFaultOracleLoader.loadTrace("v1-instance-lease-lost-then-recovered")
        )
    }

    @Test func v1GrowthThenIncumbentDeathOpensOneLeaseLost() async throws {
        try await SseFaultOracleDriver.run(
            SseFaultOracleLoader.loadTrace("v1-growth-then-incumbent-lease-lost")
        )
    }

    @Test func oldGenerationWritableChannelDoesNotPublish() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("old-generation-stays-writable"))
    }

    @Test func pathLostThenPathReadyReconcilesOnce() async throws {
        try await SseFaultOracleDriver.run(SseFaultOracleLoader.loadTrace("path-lost-then-path-ready"))
    }

    @Test func backgroundStopThenForegroundReconcilesOnce() async throws {
        try await SseFaultOracleDriver.run(
            SseFaultOracleLoader.loadTrace("background-stop-then-foreground")
        )
    }
}
