import Foundation

/// Runs profile changes one at a time, in arrival order. An actor alone does not do this: it
/// releases its isolation at every `await`, so a second change would start inside the first.
///
/// An operation may call `run` again from its own task; that nested call runs inline instead of
/// waiting on itself. Work it spawns (`Task { }`) is a different task and queues normally.
actor ProfileChangeGate {
    private struct Waiter {
        let id: UInt64
        let task: Int
        let continuation: CheckedContinuation<Void, Error>
    }

    private var holder: Int?
    private var waiters: [Waiter] = []
    private var nextWaiterID: UInt64 = 0

    /// Changes waiting behind the running one.
    var queuedCount: Int { waiters.count }

    /// `cancellable` changes leave the queue with `CancellationError` when their task is
    /// cancelled before they start. Once started, an operation always runs to its end.
    nonisolated func run<T: Sendable>(
        cancellable: Bool = true,
        _ operation: @Sendable () async throws -> T
    ) async throws -> T {
        let task = withUnsafeCurrentTask { $0?.hashValue } ?? 0
        if await isHeld(by: task) {
            return try await operation()
        }
        let id = await reserveWaiterID()
        if cancellable {
            try await withTaskCancellationHandler {
                try await acquire(id: id, task: task, honorsCancellation: true)
            } onCancel: {
                Task { await self.cancelWaiter(id: id) }
            }
        } else {
            try await acquire(id: id, task: task, honorsCancellation: false)
        }
        do {
            let result = try await operation()
            await release()
            return result
        } catch {
            await release()
            throw error
        }
    }

    private func isHeld(by task: Int) -> Bool {
        holder == task
    }

    private func reserveWaiterID() -> UInt64 {
        nextWaiterID += 1
        return nextWaiterID
    }

    private func acquire(id: UInt64, task: Int, honorsCancellation: Bool) async throws {
        if honorsCancellation {
            try Task.checkCancellation()
        }
        guard holder != nil else {
            holder = task
            return
        }
        try await withCheckedThrowingContinuation { continuation in
            waiters.append(Waiter(id: id, task: task, continuation: continuation))
        }
    }

    private func cancelWaiter(id: UInt64) {
        guard let index = waiters.firstIndex(where: { $0.id == id }) else { return }
        waiters.remove(at: index).continuation.resume(throwing: CancellationError())
    }

    private func release() {
        guard !waiters.isEmpty else {
            holder = nil
            return
        }
        let next = waiters.removeFirst()
        holder = next.task
        next.continuation.resume()
    }
}
