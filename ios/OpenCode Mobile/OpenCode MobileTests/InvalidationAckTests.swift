import Foundation
import Testing
@testable import OpenCode_Mobile

/// The invalidation ack is a spinner release, not a freshness signal, so waiting on it
/// must always be bounded: a collector can be torn down (profile switch, Clear All Data)
/// or stalled on a slow radio at any point inside the refresh it was asked to run, and
/// the waiting pull-to-refresh Task can itself be cancelled.
struct InvalidationAckTests {
    @Test func invalidateAndWait_stopsWaiting_whenNoCollectorEverAcks() async {
        let bus = StoreInvalidationBus(ackTimeoutMs: 200)
        // Subscribe before invalidating: with no subscribers the bus takes its fast path.
        let stream = bus.events()
        let received = AckCounter()
        let collector = Task {
            for await _ in stream { received.increment() } // never acks
        }
        defer { collector.cancel() }

        let done = AckFlag()
        let startedAt = Date()
        // Detached, not a child task: without the fix this wait never resumes, and a
        // child would hang the test itself instead of failing it.
        Task.detached {
            await bus.invalidateAndWait(key: nil, reason: StoreRefreshReason.pullToRefresh)
            done.mark()
        }
        let returned = await waitForAck(timeoutMs: 3_000) { done.isMarked }
        let elapsed = Date().timeIntervalSince(startedAt)

        #expect(
            returned,
            "invalidateAndWait must stop waiting when the collector never acks (waited \(elapsed)s)"
        )
        #expect(received.value == 1, "the collector must still have received the invalidation")
    }

    @Test func invalidateAndWait_stopsWaiting_whenTheCallingTaskIsCancelled() async {
        // A long ceiling: only cancellation can end this wait in time.
        let bus = StoreInvalidationBus(ackTimeoutMs: 60_000)
        let stream = bus.events()
        let collector = Task {
            for await _ in stream {} // never acks
        }
        defer { collector.cancel() }

        let done = AckFlag()
        let waiter = Task.detached {
            await bus.invalidateAndWait(key: nil, reason: StoreRefreshReason.pullToRefresh)
            done.mark()
        }
        // Let the wait get established before cancelling it.
        try? await Task.sleep(for: .milliseconds(100))
        waiter.cancel()

        let returned = await waitForAck(timeoutMs: 3_000) { done.isMarked }
        #expect(
            returned,
            "cancelling the pull-to-refresh Task must end the ack wait; a bare checked continuation is not cancellation-aware and leaks instead"
        )
    }
}

private func waitForAck(timeoutMs: Int, predicate: @Sendable () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

private final class AckFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var marked = false

    var isMarked: Bool {
        lock.lock()
        defer { lock.unlock() }
        return marked
    }

    func mark() {
        lock.lock()
        marked = true
        lock.unlock()
    }
}

private final class AckCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}
