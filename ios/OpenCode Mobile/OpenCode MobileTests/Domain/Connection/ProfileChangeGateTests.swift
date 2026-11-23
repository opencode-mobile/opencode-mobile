import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ProfileChangeGateTests {
    @Test func operationsDoNotInterleave() async throws {
        let gate = ProfileChangeGate()
        let log = Locked<[String]>([])
        let entered = GateSignal()
        let release = GateSignal()

        let first = Task {
            try await gate.run {
                log.mutate { $0.append("first-start") }
                entered.fire()
                await release.wait()
                log.mutate { $0.append("first-end") }
            }
        }
        await entered.wait()
        let second = Task {
            try await gate.run {
                log.mutate { $0.append("second") }
            }
        }
        for _ in 0 ..< 200 { await Task.yield() }
        release.fire()
        try await first.value
        try await second.value

        #expect(log.value == ["first-start", "first-end", "second"])
    }

    @Test func waitingOperationsRunInArrivalOrder() async throws {
        let gate = ProfileChangeGate()
        let log = Locked<[Int]>([])
        let entered = GateSignal()
        let release = GateSignal()
        let holder = Task {
            try await gate.run {
                entered.fire()
                await release.wait()
            }
        }
        await entered.wait()
        var queued: [Task<Void, Error>] = []
        for number in 1 ... 3 {
            queued.append(Task { try await gate.run { log.mutate { $0.append(number) } } })
            while await gate.queuedCount < number { await Task.yield() }
        }
        release.fire()
        try await holder.value
        for task in queued { try await task.value }

        #expect(log.value == [1, 2, 3])
    }

    @Test func nestedCallFromTheRunningOperationDoesNotDeadlock() async throws {
        let gate = ProfileChangeGate()
        let value = try await gate.run { try await gate.run { 7 } }
        #expect(value == 7)
    }

    @Test func cancelledWaiterLeavesTheQueueWithoutRunning() async throws {
        let gate = ProfileChangeGate()
        let ran = Locked(false)
        let entered = GateSignal()
        let release = GateSignal()
        let holder = Task {
            try await gate.run {
                entered.fire()
                await release.wait()
            }
        }
        await entered.wait()
        let waiter = Task { try await gate.run { ran.mutate { $0 = true } } }
        while await gate.queuedCount < 1 { await Task.yield() }

        waiter.cancel()
        await #expect(throws: CancellationError.self) { try await waiter.value }
        release.fire()
        try await holder.value

        #expect(ran.value == false)
        #expect(await gate.queuedCount == 0)
        try await gate.run {}
    }

    @Test func failedOperationReleasesTheGate() async throws {
        struct Failure: Error {}
        let gate = ProfileChangeGate()
        await #expect(throws: Failure.self) {
            try await gate.run { throw Failure() }
        }
        #expect(try await gate.run { 1 } == 1)
    }
}

/// One-shot signal: `wait` returns once `fire` has been called, before or after.
final class GateSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var fired = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func fire() {
        lock.lock()
        fired = true
        let pending = waiters
        waiters = []
        lock.unlock()
        pending.forEach { $0.resume() }
    }

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if fired {
                lock.unlock()
                continuation.resume()
            } else {
                waiters.append(continuation)
                lock.unlock()
            }
        }
    }
}
