import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct SceneWorldSignalsTests {
    @Test func briefBackgroundDoesNotStopStreamWhenForegroundCancelsGrace() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(stream: stream, stopGrace: .milliseconds(100))
        let collected = SignalCollector()
        let job = Task {
            for await signal in signals.signals() {
                collected.append(signal)
            }
        }
        try? await Task.sleep(for: .milliseconds(20))

        signals.onForeground()
        try? await Task.sleep(for: .milliseconds(20))
        #expect(stream.connectionState == .connected)
        #expect(collected.snapshot() == [.foreground])

        signals.onBackground()
        try? await Task.sleep(for: .milliseconds(40))
        #expect(stream.connectionState == .connected)
        #expect(collected.snapshot() == [.foreground, .background])

        signals.onForeground()
        try? await Task.sleep(for: .milliseconds(200))
        #expect(stream.connectionState == .connected)
        #expect(collected.snapshot() == [.foreground, .background, .foreground])
        job.cancel()
    }

    @Test func sustainedBackgroundStopsStreamAfterGrace() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(stream: stream, stopGrace: .milliseconds(100))
        let collected = SignalCollector()
        let job = Task {
            for await signal in signals.signals() {
                collected.append(signal)
            }
        }
        try? await Task.sleep(for: .milliseconds(20))

        signals.onForeground()
        signals.onBackground()
        try? await Task.sleep(for: .milliseconds(20))
        #expect(collected.snapshot() == [.foreground, .background])
        try? await Task.sleep(for: .milliseconds(60))
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
        try? await Task.sleep(for: .milliseconds(20))
        #expect(stream.connectionState == .connected)

        signals.onProfileSwitched()
        try? await Task.sleep(for: .milliseconds(20))
        #expect(stream.connectionState == .connected)
        #expect(stream.ops == ["wake", "start"])

        let before = stream.ops
        signals.onProfileCleared()
        try? await Task.sleep(for: .milliseconds(20))
        #expect(stream.ops == before)
        #expect(stream.connectionState == .connected)
    }

    @Test func occupancyTracksForegroundGraceAndStop() async {
        let stream = FakeForegroundEventStream()
        let signals = sceneWorldSignals(stream: stream, stopGrace: .milliseconds(50))
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
            stopGrace: .milliseconds(80),
            inactiveGrace: .milliseconds(40)
        )
        signals.onForeground()
        signals.onInactive()
        try? await Task.sleep(for: .milliseconds(20))
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
            stopGrace: .milliseconds(80),
            inactiveGrace: .milliseconds(80)
        )
        signals.onForeground()
        signals.onInactive()
        try? await Task.sleep(for: .milliseconds(20))
        signals.onForeground()
        try? await Task.sleep(for: .milliseconds(120))
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
        try? await Task.sleep(for: .milliseconds(20))
        #expect(stream.ops.filter { $0 == "wake" }.count == 2)
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
        timeoutMs: Int = 2_000,
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
