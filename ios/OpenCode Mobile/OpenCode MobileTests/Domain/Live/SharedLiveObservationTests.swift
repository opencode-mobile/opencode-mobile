import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct SharedLiveObservationTests {
    @Test func finish_resumesEveryParkedUpstreamWaiterAndAllowsReplacement() async {
        let park = ContinuationPark()
        let first = SharedLiveObservation<Int> { yield in
            Task {
                yield(1)
                await park.holdUntilCancelled()
            }
        }
        var values: [Int] = []
        let subscriber = Task {
            for await value in first.stream() {
                values.append(value)
            }
        }
        let sawFirst = await waitUntil { values == [1] }
        #expect(sawFirst)

        first.finish()
        let resumed = await waitUntil { park.resumedCount == 1 }
        #expect(resumed)
        subscriber.cancel()

        let replacement = SharedLiveObservation<Int> { yield in
            Task {
                yield(2)
                await park.holdUntilCancelled()
            }
        }
        var replaced: [Int] = []
        let second = Task {
            for await value in replacement.stream() {
                replaced.append(value)
            }
        }
        let sawSecond = await waitUntil { replaced == [2] }
        #expect(sawSecond)
        replacement.finish()
        let resumedReplacement = await waitUntil { park.resumedCount == 2 }
        second.cancel()
        #expect(resumedReplacement)
    }

    @Test func overlappingHolds_areNotOverwritten() async {
        let park = ContinuationPark()
        async let first: Void = park.holdUntilCancelled()
        async let second: Void = park.holdUntilCancelled()
        let parked = await waitUntil { park.waiterCount == 2 }
        #expect(parked)
        park.finish()
        _ = await (first, second)
        #expect(park.resumedCount == 2)
        #expect(park.waiterCount == 0)
    }

    @Test func cancelBeforeOrDuringHold_resumesExactlyOnce() async {
        let park = ContinuationPark()
        let task = Task {
            await park.holdUntilCancelled()
        }
        task.cancel()
        await task.value
        #expect(park.resumedCount == 1)
        #expect(park.waiterCount == 0)
    }
}

private final class ContinuationPark: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var shutdown = false
    private var _resumedCount = 0

    var resumedCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _resumedCount
    }

    var waiterCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return waiters.count
    }

    func holdUntilCancelled() async {
        let ticket = UUID()
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                if shutdown {
                    _resumedCount += 1
                    lock.unlock()
                    continuation.resume()
                    return
                }
                waiters[ticket] = continuation
                if Task.isCancelled, let pending = waiters.removeValue(forKey: ticket) {
                    _resumedCount += 1
                    lock.unlock()
                    pending.resume()
                    return
                }
                lock.unlock()
            }
        } onCancel: {
            resume(ticket)
        }
    }

    func finish() {
        lock.lock()
        shutdown = true
        let pending = Array(waiters.values)
        _resumedCount += pending.count
        waiters.removeAll()
        lock.unlock()
        pending.forEach { $0.resume() }
    }

    private func resume(_ ticket: UUID) {
        lock.lock()
        let pending = waiters.removeValue(forKey: ticket)
        if pending != nil { _resumedCount += 1 }
        lock.unlock()
        pending?.resume()
    }
}
