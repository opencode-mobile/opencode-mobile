import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct SceneWorldSignalsTests {
    @Test func briefBackgroundDoesNotStopStreamWhenForegroundCancelsGrace() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(stream: stream, stopGrace: .seconds(30))
        let collected = SignalCollector()
        let job = Task {
            for await signal in signals.signals() {
                collected.append(signal)
            }
        }

        signals.onForeground()
        let foregrounded = await waitUntil {
            stream.connectionState == .connected && collected.snapshot() == [.foreground]
        }
        #expect(foregrounded)

        signals.onBackground()
        let backed = await waitUntil { collected.snapshot() == [.foreground, .background] }
        #expect(backed)
        #expect(stream.connectionState == .connected)

        signals.onForeground()
        let resumed = await waitUntil {
            stream.connectionState == .connected
                && collected.snapshot() == [.foreground, .background, .foreground]
        }
        #expect(resumed)
        job.cancel()
    }

    @Test func sustainedBackgroundStopsStreamAfterGrace() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(stream: stream, stopGrace: .milliseconds(150))
        let collected = SignalCollector()
        let job = Task {
            for await signal in signals.signals() {
                collected.append(signal)
            }
        }

        signals.onForeground()
        signals.onBackground()
        let backed = await waitUntil { collected.snapshot() == [.foreground, .background] }
        #expect(backed)
        #expect(stream.connectionState == .connected)

        let stopped = await waitUntil {
            stream.connectionState == .idle
                && collected.snapshot() == [.foreground, .background, .backgroundStop]
        }
        #expect(stopped)
        job.cancel()
    }

    @Test func profileHandlerEmitsOnlyWithoutStopOrStart() async {
        let stream = RecordingForegroundEventStream()
        let signals = SceneWorldSignals(
            stream: stream,
            deviceNetworkPath: ReadyDeviceNetworkPath(),
            hasActiveConnection: { true },
            stopGrace: .milliseconds(50)
        )
        signals.onForeground()
        let started = await waitUntil { stream.connectionState == .connected }
        #expect(started)

        signals.onProfileSwitched()
        let switched = await waitUntil { stream.ops == ["wake", "start"] }
        #expect(switched)
        #expect(stream.connectionState == .connected)

        let before = stream.ops
        signals.onProfileCleared()
        let settled = await waitUntil { stream.ops == before && stream.connectionState == .connected }
        #expect(settled)
    }

    @Test func occupancyTracksForegroundGraceAndStop() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(stream: stream, stopGrace: .milliseconds(150))
        #expect(signals.occupancy == .active)

        signals.onBackground()
        #expect(signals.occupancy == .grace)

        let stopped = await waitUntil { signals.occupancy == .stopped }
        #expect(stopped)

        signals.onForeground()
        #expect(signals.occupancy == .active)
        #expect(stream.connectionState == .connected)
    }

    @Test func inactiveLongerThanGraceStartsBackgroundGrace() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(
            stream: stream,
            stopGrace: .milliseconds(150),
            inactiveGrace: .milliseconds(150)
        )
        signals.onForeground()
        signals.onInactive()
        #expect(signals.occupancy == .active)
        let graced = await waitUntil { signals.occupancy == .grace }
        #expect(graced)
        let stopped = await waitUntil { signals.occupancy == .stopped }
        #expect(stopped)
        let idle = await waitUntil { stream.connectionState == .idle }
        #expect(idle)
    }

    @Test func activeBeforeInactiveGraceCancelsBackground() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(
            stream: stream,
            stopGrace: .seconds(30),
            inactiveGrace: .seconds(30)
        )
        signals.onForeground()
        signals.onInactive()
        #expect(signals.occupancy == .active)
        signals.onForeground()
        #expect(signals.occupancy == .active)
        #expect(stream.connectionState == .connected)
    }

    @Test func foregroundAndPathReadyWakeTheStream() async {
        let stream = RecordingForegroundEventStream()
        let signals = SceneWorldSignals(
            stream: stream,
            deviceNetworkPath: ReadyDeviceNetworkPath(),
            hasActiveConnection: { true },
            stopGrace: .milliseconds(50)
        )
        signals.onForeground()
        signals.onPathReady()
        let woke = await waitUntil { stream.ops.filter { $0 == "wake" }.count == 2 }
        #expect(woke)
    }

    private func sceneWorldSignals(
        stream: FakeForegroundEventStream,
        stopGrace: Duration,
        inactiveGrace: Duration = .milliseconds(2_000)
    ) -> SceneWorldSignals {
        SceneWorldSignals(
            stream: stream,
            deviceNetworkPath: ReadyDeviceNetworkPath(),
            hasActiveConnection: { true },
            stopGrace: stopGrace,
            inactiveGrace: inactiveGrace
        )
    }

    private func waitUntil(
        timeoutMs: Int = 5_000,
        predicate: () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }
}

private final class SignalCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [WorldSignal] = []

    func append(_ signal: WorldSignal) {
        lock.lock()
        values.append(signal)
        lock.unlock()
    }

    func snapshot() -> [WorldSignal] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
}

@Suite
struct SilentWorldSignalsTests {
    @Test func signalsDoNotFinishImmediately() async {
        var finished = false
        let job = Task {
            for await _ in SilentWorldSignals.shared.signals() {}
            finished = true
        }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(!finished)
        job.cancel()
    }

    @Test func occupancyUpdatesStayOpenAfterInitialActive() async {
        var finished = false
        var sawActive = false
        let job = Task {
            for await occupancy in SilentWorldSignals.shared.occupancyUpdates() {
                if occupancy == .active { sawActive = true }
            }
            finished = true
        }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(sawActive)
        #expect(!finished)
        job.cancel()
    }
}
