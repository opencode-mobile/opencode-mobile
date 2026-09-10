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
}
