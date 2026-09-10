import Foundation

/// Per-projection reconciliation authority keyed by `LiveConnection` gap
/// revision plus local invalidation. Reconcile iff the current obligation is
/// newer than the last successfully acknowledged revision.
nonisolated final class GapAuthority: @unchecked Sendable {
    private let lock = NSLock()
    private var lastAcknowledged: UInt64 = 0
    private var localObligation: UInt64 = 0
    private var inFlight: UInt64?

    func obligation(_ snapshot: LiveConnectionSnapshot) -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return max(snapshot.gap?.generation ?? 0, localObligation)
    }

    func needsReconcile(_ snapshot: LiveConnectionSnapshot) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let target = max(snapshot.gap?.generation ?? 0, localObligation)
        return target > lastAcknowledged && inFlight != target
    }

    func tryBegin(_ snapshot: LiveConnectionSnapshot) -> UInt64? {
        lock.lock()
        defer { lock.unlock() }
        let target = max(snapshot.gap?.generation ?? 0, localObligation)
        if target <= lastAcknowledged { return nil }
        if inFlight == target { return nil }
        inFlight = target
        return target
    }

    func acknowledge(_ generation: UInt64) {
        lock.lock()
        if generation >= lastAcknowledged {
            lastAcknowledged = generation
        }
        if inFlight == generation {
            inFlight = nil
        }
        lock.unlock()
    }

    func acknowledgeCurrent(_ snapshot: LiveConnectionSnapshot) {
        acknowledge(obligation(snapshot))
    }

    func fail(_ generation: UInt64) {
        lock.lock()
        if inFlight == generation {
            inFlight = nil
        }
        lock.unlock()
    }

    func invalidate(_ snapshot: LiveConnectionSnapshot) {
        lock.lock()
        let current = max(snapshot.gap?.generation ?? 0, localObligation, lastAcknowledged)
        localObligation = current + 1
        lock.unlock()
    }
}
