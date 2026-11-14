import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ServerHealthReportBuilderTests {
    private let now: Int64 = 1_700_000_000_000
    private let minute: Int64 = 60_000
    private var hourStart: Int64 { now - ServerHealthRange.hour.durationMs }

    private func state(_ value: ServerHealthConnectionState, at offsetMinutes: Int64) -> ServerHealthEvent {
        .state(value, profileID: "p1", atMs: hourStart + offsetMinutes * minute)
    }

    private func build(
        pings: [ServerHealthPing] = [],
        events: [ServerHealthEvent],
        range: ServerHealthRange = .hour
    ) -> ServerHealthReport {
        ServerHealthReportBuilder.build(pings: pings, events: events, nowMs: now, range: range)
    }

    @Test func uptimeCountsOnlyObservedTime() {
        let report = build(events: [
            state(.live, at: 0),
            state(.stopped, at: 20),
            state(.live, at: 40),
            state(.reconnecting, at: 50),
            state(.live, at: 55)
        ])
        #expect(report.observedMs == 40 * minute)
        #expect(report.liveMs == 35 * minute)
        #expect(report.uptimePercent == 87.5)
    }

    @Test func uptimeIsNilWithNothingObserved() {
        let report = build(events: [state(.stopped, at: 10)])
        #expect(report.observedMs == 0)
        #expect(report.uptimePercent == nil)
    }

    @Test func liveToStoppedIsNotADrop() {
        let report = build(events: [
            state(.live, at: 0),
            state(.stopped, at: 10),
            state(.connecting, at: 20),
            state(.live, at: 21)
        ])
        #expect(report.drops == 0)
        #expect(report.meanTimeBetweenDropsMs == nil)
        #expect(report.medianRecoveryMs == nil)
    }

    @Test func connectingAfterStoppedIsNotADrop() {
        let report = build(events: [
            state(.stopped, at: 0),
            state(.connecting, at: 5),
            state(.reconnecting, at: 6),
            state(.live, at: 7)
        ])
        #expect(report.drops == 0)
    }

    @Test func dropsFromLiveIntoEachTroubleStateCount() {
        let report = build(events: [
            state(.live, at: 0),
            state(.reconnecting, at: 10),
            state(.live, at: 11),
            state(.offline, at: 20),
            state(.live, at: 22),
            state(.authFailed, at: 30),
            state(.live, at: 33),
            state(.connecting, at: 40),
            state(.live, at: 44)
        ])
        #expect(report.drops == 4)
        #expect(report.meanTimeBetweenDropsMs == report.liveMs / 4)
    }

    @Test func recoveryAcrossStoppedIsExcluded() {
        let report = build(events: [
            state(.live, at: 0),
            state(.offline, at: 10),
            state(.stopped, at: 15),
            state(.live, at: 30),
            state(.reconnecting, at: 40),
            state(.live, at: 42)
        ])
        #expect(report.drops == 2)
        #expect(report.medianRecoveryMs == 2 * minute)
    }

    @Test func recoveryStillGoingIsExcluded() {
        let report = build(events: [state(.live, at: 0), state(.reconnecting, at: 50)])
        #expect(report.drops == 1)
        #expect(report.medianRecoveryMs == nil)
    }

    @Test func medianRecoveryAveragesTheTwoMiddlesWhenEven() {
        let report = build(events: [
            state(.live, at: 0),
            state(.reconnecting, at: 10),
            state(.live, at: 11),
            state(.reconnecting, at: 20),
            state(.live, at: 23)
        ])
        #expect(report.medianRecoveryMs == 2 * minute)
    }

    @Test func medianPicksTheMiddleWhenOdd() {
        #expect(ServerHealthReportBuilder.median([5, 1, 3]) == 3)
        #expect(ServerHealthReportBuilder.median([1, 4]) == 2)
        #expect(ServerHealthReportBuilder.median([4, 1, 9, 7]) == 5)
        #expect(ServerHealthReportBuilder.median([]) == nil)
    }

    @Test func percentilesUseNearestRank() {
        let twenty = Array(1 ... 20)
        #expect(ServerHealthReportBuilder.percentile(twenty, 95) == 19)
        #expect(ServerHealthReportBuilder.percentile(twenty, 50) == 10)
        #expect(ServerHealthReportBuilder.percentile([10, 20, 30], 95) == 30)
        #expect(ServerHealthReportBuilder.percentile([10, 20, 30], 50) == 20)
        #expect(ServerHealthReportBuilder.percentile(Array(1 ... 100), 95) == 95)
        #expect(ServerHealthReportBuilder.percentile([7], 95) == 7)
        #expect(ServerHealthReportBuilder.percentile([], 50) == nil)
    }

    @Test func reportLatencyUsesSuccessfulChecksInRange() {
        let pings = (1 ... 20).map { ServerHealthPing.success(profileID: "p1", atMs: hourStart + Int64($0) * minute, latencyMs: $0 * 10) }
            + [
                .failed(profileID: "p1", atMs: hourStart + 30 * minute, failure: .timeout),
                .success(profileID: "p1", atMs: hourStart - minute, latencyMs: 9999)
            ]
        let report = build(pings: pings, events: [state(.live, at: 0)])
        #expect(report.checks == 21)
        #expect(report.failedChecks == 1)
        #expect(abs((report.errorRatePercent ?? 0) - 100.0 / 21.0) < 1e-9)
        #expect(report.p50LatencyMs == 100)
        #expect(report.p95LatencyMs == 190)
    }

    @Test func errorRateIsNilWithoutChecks() {
        #expect(build(events: [state(.live, at: 0)]).errorRatePercent == nil)
    }

    @Test func bucketConditionsTreatConnectingAsNeutral() {
        let second: Int64 = 1000
        let events: [ServerHealthEvent] = [
            // Bucket 0: connecting all minute.
            .state(.connecting, profileID: "p1", atMs: hourStart),
            // Bucket 1: live all minute.
            .state(.live, profileID: "p1", atMs: hourStart + minute),
            // Bucket 2: reconnecting 20 s of 60 s, entered from live: a drop.
            .state(.reconnecting, profileID: "p1", atMs: hourStart + 2 * minute + 40 * second),
            // Bucket 3: offline 40 s of 60 s.
            .state(.live, profileID: "p1", atMs: hourStart + 3 * minute),
            .state(.offline, profileID: "p1", atMs: hourStart + 3 * minute + 20 * second),
            // Bucket 4 onwards: stopped.
            .state(.stopped, profileID: "p1", atMs: hourStart + 4 * minute)
        ]
        let report = build(events: events)
        #expect(report.buckets.count == 60)
        #expect(report.buckets[0].condition == .live)
        #expect(report.buckets[1].condition == .live)
        #expect(report.buckets[2].condition == .degraded)
        #expect(report.buckets[2].drops == 1)
        #expect(report.buckets[3].condition == .down)
        #expect(report.buckets[3].drops == 1)
        #expect(report.buckets[4].condition == .unobserved)
        #expect(report.buckets[59].condition == .unobserved)
    }

    @Test func bucketWithADropButNoTroubleTimeIsDegraded() {
        let report = build(events: [
            .state(.live, profileID: "p1", atMs: hourStart),
            .state(.connecting, profileID: "p1", atMs: hourStart + 5 * minute + 10_000),
            .state(.live, profileID: "p1", atMs: hourStart + 5 * minute + 20_000)
        ])
        #expect(report.buckets[5].condition == .degraded)
        #expect(report.buckets[5].drops == 1)
    }

    @Test func bucketsCarryLatencyAndChecks() {
        let pings: [ServerHealthPing] = [
            .success(profileID: "p1", atMs: hourStart + 10 * minute + 1000, latencyMs: 20),
            .success(profileID: "p1", atMs: hourStart + 10 * minute + 31_000, latencyMs: 40),
            .failed(profileID: "p1", atMs: hourStart + 10 * minute + 50_000, failure: .network),
            .success(profileID: "p1", atMs: now, latencyMs: 15)
        ]
        let report = build(pings: pings, events: [state(.live, at: 0)])
        #expect(report.buckets[10].checks == 3)
        #expect(report.buckets[10].failedChecks == 1)
        #expect(report.buckets[10].medianMs == 20)
        #expect(report.buckets[10].p95Ms == 40)
        #expect(report.buckets[59].checks == 1)
        #expect(report.buckets[11].medianMs == nil)
    }

    @Test func bucketCountsFollowTheRange() {
        #expect(build(events: [], range: .day).buckets.count == 96)
        #expect(build(events: [], range: .week).buckets.count == 84)
    }

    @Test func failuresMergeStreamEventsAndFailedChecksByCountThenOrder() {
        let at = hourStart + 5 * minute
        let events: [ServerHealthEvent] = [
            state(.live, at: 0),
            .failure(.timeout, profileID: "p1", atMs: at),
            .failure(.timeout, profileID: "p1", atMs: at),
            .failure(.server, profileID: "p1", atMs: at),
            .failure(.heartbeat, profileID: "p1", atMs: hourStart - minute)
        ]
        let pings: [ServerHealthPing] = [
            .failed(profileID: "p1", atMs: at, failure: .timeout),
            .failed(profileID: "p1", atMs: at, failure: .network),
            .failed(profileID: "p1", atMs: at, failure: .network),
            .failed(profileID: "p1", atMs: at, failure: .server)
        ]
        let report = build(pings: pings, events: events)
        #expect(report.failuresByCategory == [
            ServerHealthFailureCount(category: .timeout, count: 3),
            ServerHealthFailureCount(category: .network, count: 2),
            ServerHealthFailureCount(category: .server, count: 2)
        ])
    }

    @Test func initialStateComesFromTheEventBeforeTheRange() {
        let report = build(events: [.state(.live, profileID: "p1", atMs: hourStart - 10 * minute)])
        #expect(report.observedMs == ServerHealthRange.hour.durationMs)
        #expect(report.uptimePercent == 100)
        #expect(report.drops == 0)
    }

    @Test func transitionFromStateBeforeTheRangeCountsAsADrop() {
        let report = build(events: [
            .state(.live, profileID: "p1", atMs: hourStart - 10 * minute),
            state(.offline, at: 30)
        ])
        #expect(report.drops == 1)
        #expect(report.uptimePercent == 50)
    }

    @Test func restartsInRangeAndLastRestartAcrossAllData() {
        let events: [ServerHealthEvent] = [
            .restart(version: "1.2.0", previousVersion: "1.1.0", profileID: "p1", atMs: hourStart - 5 * minute),
            .restart(version: nil, previousVersion: "1.2.0", profileID: "p1", atMs: hourStart + 10 * minute)
        ]
        let hour = build(events: events)
        #expect(hour.restarts == [ServerHealthRestart(atMs: hourStart + 10 * minute, version: nil, previousVersion: "1.2.0")])
        #expect(hour.lastRestartAtMs == hourStart + 10 * minute)

        let early = ServerHealthReportBuilder.build(pings: [], events: [events[0]], nowMs: now, range: .hour)
        #expect(early.restarts.isEmpty)
        #expect(early.lastRestartAtMs == hourStart - 5 * minute)
    }

    @Test func emptyOnlyWithNoChecksAndNoEvents() {
        #expect(build(events: []).isEmpty)
        #expect(!build(events: [.state(.live, profileID: "p1", atMs: hourStart - 2 * ServerHealthRange.hour.durationMs)]).isEmpty)
    }

    @Test func recentPingsAreChronologicalWithFailuresAsNil() {
        let pings: [ServerHealthPing] = [
            .success(profileID: "p1", atMs: now - 5 * minute, latencyMs: 30),
            .failed(profileID: "p1", atMs: now - 10 * minute, failure: .network),
            .success(profileID: "p1", atMs: now - 20 * minute, latencyMs: 20),
            .success(profileID: "p1", atMs: now - 40 * minute, latencyMs: 99)
        ]
        #expect(ServerHealthReportBuilder.recentPings(pings, nowMs: now) == [20, nil, 30])
    }
}
