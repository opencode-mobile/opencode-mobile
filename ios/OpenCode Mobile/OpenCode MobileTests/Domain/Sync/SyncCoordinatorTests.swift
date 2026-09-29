import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct SyncCoordinatorTests {
    @Test func registeredProjectionStartsStaleUntilLoaderSucceeds() async {
        let env = CoordinatorHarness()
        let key = ProjectionKey.sessionList(directory: "/repo")
        let loads = Locked(0)
        let registration = env.coordinator.register(key: key) {
            loads.mutate { $0 += 1 }
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)

        let states = StateCollector()
        let task = Task { await states.collect(env.coordinator.observe(key)) }
        _ = await coordinatorWaitUntil { states.latest == .stale }
        await env.coordinator.ensureFresh(key)
        _ = await coordinatorWaitUntil { states.latest == .fresh }
        task.cancel()
        #expect(states.values.contains(.stale))
        #expect(states.values.contains(.syncing))
        #expect(states.latest == .fresh)
        #expect(loads.value == 1)
    }

    @Test func secondEnsureFreshWhileSyncingIsNoOp() async {
        let env = CoordinatorHarness()
        let key = ProjectionKey.sessionStatuses(directory: "/repo")
        let loads = Locked(0)
        let registration = env.coordinator.register(key: key) { @Sendable in
            await Task.detached {
                loads.mutate { $0 += 1 }
                try? await Task.sleep(for: .milliseconds(80))
            }.value
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)

        let first = Task.detached { await env.coordinator.ensureFresh(key) }
        let started = await coordinatorWaitUntil { loads.value == 1 }
        #expect(started)
        let second = Task.detached { await env.coordinator.ensureFresh(key) }
        _ = await first.value
        _ = await second.value
        #expect(loads.value == 1)
    }

    @Test func loaderFailureRetriesWithBackoffUntilSuccess() async {
        let clock = FakeSyncClock()
        let env = CoordinatorHarness(clock: clock)
        let key = ProjectionKey.permissions(directory: "/repo")
        let attempts = Locked(0)
        let registration = env.coordinator.register(key: key) {
            attempts.mutate { $0 += 1 }
            if attempts.value < 3 {
                throw URLError(.cannotConnectToHost)
            }
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)

        let states = StateCollector()
        let task = Task.detached { await states.collect(env.coordinator.observe(key)) }
        let load = Task.detached { await env.coordinator.ensureFresh(key) }
        _ = await coordinatorWaitUntil { attempts.value == 1 }
        // The observer runs on its own task; wait for the first state to land.
        let sawStale = await coordinatorWaitUntil { states.latest == .stale }
        #expect(sawStale)
        _ = await coordinatorWaitUntil { clock.waitersCount > 0 }
        clock.releaseNextSleep()
        _ = await coordinatorWaitUntil { attempts.value == 2 }
        _ = await coordinatorWaitUntil { clock.waitersCount > 0 }
        clock.releaseNextSleep()
        _ = await coordinatorWaitUntil { attempts.value == 3 && states.latest == .fresh }
        _ = await load.value
        task.cancel()
        #expect(attempts.value == 3)
        #expect(clock.releasedDelays.prefix(2) == [500, 1_000])
    }

    @Test func invalidateDuringLoadReMarksStaleAndReloads() async {
        let env = CoordinatorHarness()
        let key = ProjectionKey.questions(directory: "/repo")
        let loads = Locked(0)
        let registration = env.coordinator.register(key: key) { @Sendable in
            await Task.detached {
                loads.mutate { $0 += 1 }
                if loads.value == 1 {
                    try? await Task.sleep(for: .milliseconds(50))
                }
            }.value
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)

        let first = Task.detached { await env.coordinator.ensureFresh(key) }
        _ = await coordinatorWaitUntil { loads.value == 1 }
        env.coordinator.invalidate(key)
        _ = await first.value
        _ = await coordinatorWaitUntil { loads.value == 2 }
        #expect(loads.value == 2)
        let states = StateCollector()
        let task = Task.detached { await states.collect(env.coordinator.observe(key)) }
        _ = await coordinatorWaitUntil { states.latest == .fresh }
        task.cancel()
        #expect(states.latest == .fresh)
    }

    @Test func bootstrapLoadsStatusesAndFocusedTranscriptInParallelThenListsThenOthers() async {
        let env = CoordinatorHarness()
        let order = Locked([String]())
        let firstWaveStarted = Locked(Set<String>())
        let firstWaveGate = LoaderGate()
        func register(_ key: ProjectionKey, label: String, firstWave: Bool = false) -> SyncRegistration {
            let registration = env.coordinator.register(key: key) {
                if firstWave {
                    firstWaveStarted.mutate { $0.insert(label) }
                    if firstWaveStarted.value.count == 2 {
                        firstWaveGate.release()
                    }
                    await firstWaveGate.wait()
                }
                order.mutate { $0.append(label) }
            }
            env.coordinator.visible(key, true)
            return registration
        }
        let registrations = [
            register(.todos(directory: "/repo"), label: "todos"),
            register(.permissions(directory: "/repo"), label: "permissions"),
            register(.sessionList(directory: "/repo"), label: "list"),
            register(.sessionStatuses(directory: "/repo"), label: "statuses", firstWave: true),
            register(.transcript(directory: "/repo", sessionID: "ses-1"), label: "transcript", firstWave: true),
        ]
        defer { registrations.forEach { env.coordinator.unregister($0) } }
        env.coordinator.markFocused(.transcript(directory: "/repo", sessionID: "ses-1"))
        await env.coordinator.bootstrap(.manual)
        #expect(Set(order.value.prefix(2)) == ["statuses", "transcript"])
        #expect(order.value.dropFirst(2).first == "list")
        #expect(Set(order.value.suffix(from: 3)) == ["todos", "permissions"])
        #expect(order.value.count == 5)
    }

    @Test func bootstrapLeavesInvisibleProjectionsStale() async {
        let env = CoordinatorHarness()
        let loads = Locked(0)
        let hidden = ProjectionKey.sessionList(directory: "/hidden")
        let visible = ProjectionKey.sessionStatuses(directory: "/repo")
        let hiddenReg = env.coordinator.register(key: hidden) {
            loads.mutate { $0 += 1 }
        }
        let visibleReg = env.coordinator.register(key: visible) {}
        defer {
            env.coordinator.unregister(hiddenReg)
            env.coordinator.unregister(visibleReg)
        }
        env.coordinator.visible(visible, true)
        await env.coordinator.bootstrap(.manual)
        var hiddenState: ProjectionState = .fresh
        for await state in env.coordinator.observe(hidden) {
            hiddenState = state
            break
        }
        #expect(hiddenState == .stale)
        #expect(loads.value == 0)
    }

    @Test func foregroundQuietBootstrapsAfterTwentySeconds() async {
        let clock = FakeSyncClock(now: 0)
        let env = CoordinatorHarness(clock: clock)
        let key = ProjectionKey.sessionList(directory: "/repo")
        let loads = Locked(0)
        let registration = env.coordinator.register(key: key) {
            loads.mutate { $0 += 1 }
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)
        await env.coordinator.ensureFresh(key)
        #expect(loads.value == 1)

        clock.now = 19_000
        env.world.emit(.foreground)
        let held = await coordinatorWaitUntil(timeoutMs: 200) { loads.value == 2 }
        #expect(!held)
        #expect(loads.value == 1)

        clock.now = 21_000
        env.world.emit(.foreground)
        let quietReloaded = await coordinatorWaitUntil { loads.value == 2 }
        #expect(quietReloaded)
    }

    @Test func serverConnectedAndStreamReconnectedCoalesceIntoOneBootstrap() async {
        let clock = FakeSyncClock()
        let env = CoordinatorHarness(clock: clock)
        let key = ProjectionKey.sessionList(directory: "/repo")
        let loads = Locked(0)
        let registration = env.coordinator.register(key: key) {
            loads.mutate { $0 += 1 }
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)
        await env.coordinator.ensureFresh(key)
        #expect(loads.value == 1)

        let listening = await coordinatorWaitUntil { env.stream.hasEventSubscriber }
        #expect(listening)
        env.coordinator.invalidate(key)
        env.stream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        env.stream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        let coalescing = await coordinatorWaitUntil { clock.waitersCount > 0 }
        #expect(coalescing)
        clock.releaseNextSleep()
        let reloaded = await coordinatorWaitUntil { loads.value == 2 }
        #expect(reloaded)
    }

    @Test func ensureFreshDoesNotLoadWhenCannotCatchUp() async {
        let env = CoordinatorHarness()
        env.stream.setConnectionState(.connecting)
        let key = ProjectionKey.sessionList(directory: "/repo")
        let loads = Locked(0)
        let registration = env.coordinator.register(key: key) {
            loads.mutate { $0 += 1 }
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)
        await env.coordinator.ensureFresh(key)
        var state: ProjectionState = .fresh
        for await current in env.coordinator.observe(key) {
            state = current
            break
        }
        #expect(state == .stale)
        #expect(loads.value == 0)
    }

    @Test func leavingConnectedInvalidatesAll() async {
        let env = CoordinatorHarness()
        let key = ProjectionKey.sessionList(directory: "/repo")
        let registration = env.coordinator.register(key: key) {}
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)
        await env.coordinator.ensureFresh(key)
        let states = StateCollector()
        let task = Task { await states.collect(env.coordinator.observe(key)) }
        _ = await coordinatorWaitUntil { states.latest == .fresh }
        env.stream.setConnectionState(.reconnecting)
        _ = await coordinatorWaitUntil { states.latest == .stale }
        task.cancel()
        #expect(states.latest == .stale)
    }

    @Test func beginProfileSwitchDiscardsInFlightLoaderFromOldGeneration() async {
        let env = CoordinatorHarness()
        let key = ProjectionKey.sessionList(directory: "/repo")
        let started = Locked(false)
        let gate = LoaderGate()
        let registration = env.coordinator.register(key: key) { @Sendable in
            started.mutate { $0 = true }
            await gate.wait()
        }
        defer { env.coordinator.unregister(registration) }
        env.coordinator.visible(key, true)

        let load = Task.detached { await env.coordinator.ensureFresh(key) }
        _ = await coordinatorWaitUntil { started.value }
        env.coordinator.beginProfileSwitch(generation: 7)
        gate.release()
        _ = await load.value

        var state: ProjectionState = .fresh
        for await current in env.coordinator.observe(key) {
            state = current
            break
        }
        #expect(state == .stale)
        #expect(env.coordinator.profileGeneration == 7)
    }
}

private final class CoordinatorHarness: @unchecked Sendable {
    let stream: FakeForegroundEventStream
    let world: FakeWorldSignals
    let coordinator: DefaultSyncCoordinator

    init(clock: any SyncClock = SystemSyncClock()) {
        let stream = FakeForegroundEventStream()
        stream.setConnectionState(.connected)
        let world = FakeWorldSignals()
        self.stream = stream
        self.world = world
        coordinator = DefaultSyncCoordinator(
            connection: stream,
            worldSignals: world,
            eventStream: stream,
            clock: clock
        )
    }
}

private final class StateCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [ProjectionState] = []

    var values: [ProjectionState] {
        lock.lock()
        defer { lock.unlock() }
        return _values
    }

    var latest: ProjectionState? {
        values.last
    }

    func collect(_ stream: AsyncStream<ProjectionState>) async {
        for await value in stream {
            lock.lock()
            _values.append(value)
            lock.unlock()
        }
    }
}

private final class FakeSyncClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    var now: Int64
    private var waiters: [(delay: Int, continuation: CheckedContinuation<Void, Never>)] = []
    private(set) var releasedDelays: [Int] = []

    init(now: Int64 = 1_000) {
        self.now = now
    }

    var waitersCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return waiters.count
    }

    func nowMs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return now
    }

    func sleep(milliseconds: Int) async {
        await withCheckedContinuation { continuation in
            lock.lock()
            waiters.append((milliseconds, continuation))
            lock.unlock()
        }
    }

    func releaseNextSleep() {
        lock.lock()
        guard !waiters.isEmpty else {
            lock.unlock()
            return
        }
        let next = waiters.removeFirst()
        releasedDelays.append(next.delay)
        lock.unlock()
        next.continuation.resume()
    }
}

private func coordinatorWaitUntil(
    timeoutMs: Int = 5_000,
    _ predicate: @escaping @Sendable () -> Bool
) async -> Bool {
    await Task.detached {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }.value
}

private final class LoaderGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false

    func wait() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if released {
                lock.unlock()
                continuation.resume()
            } else {
                self.continuation = continuation
                lock.unlock()
            }
        }
    }

    func release() {
        lock.lock()
        released = true
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume()
    }
}
