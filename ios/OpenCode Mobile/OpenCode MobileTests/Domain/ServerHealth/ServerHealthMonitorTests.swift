import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite struct ServerHealthMonitorTests {
    private final class Clock {
        var now: Int64 = 1_700_000_000_000
    }

    private let clock = Clock()

    private func makeMonitor(
        store: InMemoryServerHealthStore = InMemoryServerHealthStore(),
        live: ServerHealthFakeLiveConnection = ServerHealthFakeLiveConnection(),
        world: FakeWorldSignals = FakeWorldSignals(),
        pinger: ServerHealthFakePinger = ServerHealthFakePinger(),
        pingTarget: @escaping @MainActor () -> ServerHealthPingTarget? = { nil },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { _ in throw CancellationError() },
        failures: ServerHealthStreamFailureRelay = ServerHealthStreamFailureRelay()
    ) -> ServerHealthMonitor {
        let clock = clock
        return ServerHealthMonitor(
            liveConnection: live,
            worldSignals: world,
            streamFailures: { failures.failures() },
            store: store,
            pinger: pinger,
            pingTarget: pingTarget,
            now: { clock.now },
            sleep: sleep
        )
    }

    private func states(_ store: InMemoryServerHealthStore, _ profileID: String = "p1") -> [String] {
        store.allEvents.filter { $0.profileID == profileID && $0.kind == .state }.map(\.value)
    }

    private func snapshot(_ profileID: String?, _ state: EventStreamConnectionState) -> ServerHealthMonitor.Input {
        .snapshot(ServerHealthTestSupport.snapshot(profileID: profileID, state: state))
    }

    @Test func recordsAStateOnlyWhenItChanges() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connecting))
        clock.now += 1000
        await monitor.handle(snapshot("p1", .connecting))
        await monitor.handle(snapshot("p1", .connected))
        await monitor.handle(snapshot("p1", .connected))
        #expect(states(store) == ["connecting", "live"])
    }

    @Test func firstStoppedStateIsNotRecorded() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .idle))
        #expect(store.allEvents.isEmpty)
    }

    @Test func occupancyStoppedRecordsStoppedOnce() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connected))
        await monitor.handle(.occupancy(.grace))
        #expect(states(store) == ["live"])
        await monitor.handle(.occupancy(.stopped))
        await monitor.handle(.occupancy(.stopped))
        await monitor.handle(snapshot("p1", .idle))
        #expect(states(store) == ["live", "stopped"])
        #expect(monitor.liveStatus.state == .stopped)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connected))
        #expect(states(store) == ["live", "stopped", "live"])
    }

    @Test func profileSwitchStopsThePreviousProfile() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connected))
        await monitor.handle(snapshot("p2", .connecting))
        #expect(states(store, "p1") == ["live", "stopped"])
        #expect(states(store, "p2") == ["connecting"])
        #expect(monitor.liveStatus.profileID == "p2")
        await monitor.handle(snapshot(nil, .idle))
        #expect(states(store, "p2") == ["connecting", "stopped"])
        #expect(monitor.liveStatus == .stopped)
    }

    @Test func relaunchAfterAKillClosesTheOpenStateAtTheLastRecord() async {
        let store = InMemoryServerHealthStore(
            pings: [.success(profileID: "p1", atMs: clock.now - 3000, latencyMs: 20)],
            events: [.state(.live, profileID: "p1", atMs: clock.now - 5000)]
        )
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connected))
        let recorded = store.allEvents.filter { $0.kind == .state }
        #expect(recorded.map(\.value) == ["live", "stopped", "live"])
        #expect(recorded[1].atMs == clock.now - 3000)
    }

    @Test func relaunchAfterACleanStopDoesNotDuplicate() async {
        let store = InMemoryServerHealthStore(events: [
            .state(.live, profileID: "p1", atMs: clock.now - 5000),
            .state(.stopped, profileID: "p1", atMs: clock.now - 4000)
        ])
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connecting))
        #expect(states(store) == ["live", "stopped", "connecting"])
    }

    @Test func snapshotWithoutConfigFallsBackToTheActiveProfile() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store, pingTarget: { ServerHealthTestSupport.target("p9") })
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot(nil, .connecting))
        #expect(states(store, "p9") == ["connecting"])
        #expect(monitor.liveStatus.profileID == "p9")
    }

    @Test func recordsOneFailurePerDropEpisode() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connected))
        await monitor.handle(.streamFailure(.heartbeat))
        await monitor.handle(snapshot("p1", .reconnecting))
        await monitor.handle(.streamFailure(.network))
        await monitor.handle(.streamFailure(.timeout))
        #expect(store.allEvents.filter { $0.kind == .failure }.map(\.value) == ["heartbeat"])
        await monitor.handle(snapshot("p1", .connected))
        await monitor.handle(.streamFailure(.server))
        #expect(store.allEvents.filter { $0.kind == .failure }.map(\.value) == ["heartbeat", "server"])
    }

    @Test func failureWithoutAProfileIsIgnored() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.streamFailure(.network))
        #expect(store.allEvents.isEmpty)
    }

    @Test func detectsARestartByPid() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.ping(profileID: "p1", atMs: 1, outcome: .success(latencyMs: 20, pid: 100, version: "1.0.0")))
        await monitor.handle(.ping(profileID: "p1", atMs: 2, outcome: .success(latencyMs: 20, pid: 100, version: "1.0.0")))
        #expect(store.allEvents.isEmpty)
        await monitor.handle(.ping(profileID: "p1", atMs: 3, outcome: .success(latencyMs: 20, pid: 200, version: "1.1.0")))
        #expect(store.allEvents == [ServerHealthEvent(profileID: "p1", atMs: 3, kind: .restart, value: "1.1.0", detail: "1.0.0")])
    }

    @Test func detectsARestartByVersionWhenNoPidIsReported() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.ping(profileID: "p1", atMs: 1, outcome: .success(latencyMs: 20, pid: nil, version: "1.0.0")))
        await monitor.handle(.ping(profileID: "p1", atMs: 2, outcome: .success(latencyMs: 20, pid: nil, version: "1.0.0")))
        await monitor.handle(.ping(profileID: "p1", atMs: 3, outcome: .success(latencyMs: 20, pid: nil, version: "1.1.0")))
        #expect(store.allEvents.map(\.kind) == [.restart])
        #expect(store.allEvents.first?.value == "1.1.0")
        #expect(store.allEvents.first?.detail == "1.0.0")
    }

    @Test func restartDetectionRemembersThePidAcrossLaunches() async {
        let t = clock.now
        let store = InMemoryServerHealthStore(pings: [.success(profileID: "p1", atMs: t - 3, latencyMs: 9, pid: 100, version: "1.0.0")])
        let monitor = makeMonitor(store: store)
        await monitor.handle(.ping(profileID: "p1", atMs: t - 2, outcome: .success(latencyMs: 20, pid: 100, version: "1.0.0")))
        #expect(store.allEvents.isEmpty)
        let relaunched = makeMonitor(store: store)
        await relaunched.handle(.ping(profileID: "p1", atMs: t - 1, outcome: .success(latencyMs: 20, pid: 101, version: nil)))
        #expect(store.allEvents.map(\.kind) == [.restart])
        #expect(store.allEvents.first?.value == "")
    }

    @Test func firstEverCheckIsNotARestart() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.ping(profileID: "p1", atMs: 1, outcome: .success(latencyMs: 20, pid: 7, version: "1.0.0")))
        #expect(store.allEvents.isEmpty)
        #expect(ServerHealthMonitor.isRestart(previous: .init(pid: 1, version: nil), pid: nil, version: "2") == false)
    }

    @Test func checksAreStoredAndUpdateLiveStatus() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(.snapshot(ServerHealthTestSupport.snapshot(profileID: "p1", state: .connected, required: ["a", "b"], live: ["a"])))
        let before = monitor.revision
        await monitor.handle(.ping(profileID: "p1", atMs: 5, outcome: .success(latencyMs: 23, pid: 1, version: "1.4.0")))
        #expect(monitor.liveStatus == ServerHealthLiveStatus(
            profileID: "p1",
            state: .live,
            lastPingMs: 23,
            lastVersion: "1.4.0",
            liveTargets: 1,
            requiredTargets: 2
        ))
        await monitor.handle(.ping(profileID: "p1", atMs: 6, outcome: .failure(.timeout)))
        #expect(monitor.liveStatus.lastPingMs == nil)
        #expect(monitor.revision == before + 2)
        #expect(store.allPings == [
            .success(profileID: "p1", atMs: 5, latencyMs: 23, pid: 1, version: "1.4.0"),
            .failed(profileID: "p1", atMs: 6, failure: .timeout)
        ])
    }

    @Test func forgetDropsCachedStateAndBumpsRevision() async {
        let store = InMemoryServerHealthStore()
        let monitor = makeMonitor(store: store)
        await monitor.handle(.occupancy(.active))
        await monitor.handle(snapshot("p1", .connected))
        await store.purge(profileID: "p1")
        let before = monitor.revision
        monitor.forget(profileID: "p1")
        #expect(monitor.revision == before + 1)
        await monitor.handle(snapshot("p1", .reconnecting))
        await monitor.handle(snapshot("p1", .connected))
        #expect(states(store) == ["reconnecting", "live"])
    }

    @Test func prunesOldRecordsOnStartAndHourly() async {
        let old = clock.now - ServerHealthRetention.windowMs - 1
        let store = InMemoryServerHealthStore(
            pings: [.failed(profileID: "p1", atMs: old, failure: .network)],
            events: [.state(.live, profileID: "p1", atMs: old)]
        )
        let monitor = makeMonitor(store: store)
        monitor.start()
        #expect(await ServerHealthTestSupport.eventually { store.allPings.isEmpty && store.allEvents.isEmpty })
        monitor.stop()
    }

    @Test func checksRunOnlyWhileActive() async {
        let store = InMemoryServerHealthStore()
        let live = ServerHealthFakeLiveConnection()
        let world = FakeWorldSignals()
        let pinger = ServerHealthFakePinger()
        let sleeper = ServerHealthManualSleeper()
        live.publish(ServerHealthTestSupport.snapshot(profileID: "p1", state: .connected, live: ["global"]))
        let monitor = makeMonitor(
            store: store,
            live: live,
            world: world,
            pinger: pinger,
            pingTarget: { ServerHealthTestSupport.target("p1") },
            sleep: { try await sleeper.sleep($0) }
        )
        monitor.start()

        // Pings at once, then waits for the interval.
        #expect(await ServerHealthTestSupport.eventually { store.allPings.count == 1 && sleeper.waiting == 1 })
        sleeper.releaseAll()
        #expect(await ServerHealthTestSupport.eventually { store.allPings.count == 2 && sleeper.waiting == 1 })

        // Grace stops the loop: the pending sleep is cancelled and no check follows.
        world.setOccupancy(.grace)
        #expect(await ServerHealthTestSupport.eventually { sleeper.waiting == 0 })
        sleeper.releaseAll()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(pinger.callCount == 2)
        #expect(store.allPings.count == 2)

        // Back to active: checks again at once.
        world.setOccupancy(.active)
        #expect(await ServerHealthTestSupport.eventually { store.allPings.count == 3 })

        world.setOccupancy(.stopped)
        #expect(await ServerHealthTestSupport.eventually { sleeper.waiting == 0 })
        #expect(states(store) == ["live", "stopped"])
        monitor.stop()
    }

    @Test func noChecksWithoutAnActiveProfile() async {
        let store = InMemoryServerHealthStore()
        let pinger = ServerHealthFakePinger()
        let sleeper = ServerHealthManualSleeper()
        // No stream config and no active profile to fall back to.
        let monitor = makeMonitor(
            store: store,
            pinger: pinger,
            pingTarget: { nil },
            sleep: { try await sleeper.sleep($0) }
        )
        monitor.start()
        try? await Task.sleep(for: .milliseconds(80))
        #expect(pinger.callCount == 0)
        monitor.stop()
    }

    @Test func streamFailuresArriveThroughTheRelay() async {
        let store = InMemoryServerHealthStore()
        let live = ServerHealthFakeLiveConnection()
        let relay = ServerHealthStreamFailureRelay()
        live.publish(ServerHealthTestSupport.snapshot(profileID: "p1", state: .connected))
        let monitor = makeMonitor(store: store, live: live, failures: relay)
        monitor.start()
        #expect(await ServerHealthTestSupport.eventually { states(store) == ["live"] })
        relay.onStreamFailure(.heartbeat)
        #expect(await ServerHealthTestSupport.eventually {
            store.allEvents.contains { $0.kind == .failure && $0.value == "heartbeat" }
        })
        monitor.stop()
    }
}
