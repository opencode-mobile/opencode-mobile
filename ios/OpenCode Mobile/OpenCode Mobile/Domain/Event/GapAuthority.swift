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
        return target > lastAcknowledged && inFlight == nil
    }

    func tryBegin(_ snapshot: LiveConnectionSnapshot) -> UInt64? {
        lock.lock()
        defer { lock.unlock() }
        let target = max(snapshot.gap?.generation ?? 0, localObligation)
        if target <= lastAcknowledged { return nil }
        if inFlight != nil { return nil }
        inFlight = target
        return target
    }

    /// Marks the current obligation in-flight so a force REST can ack only that
    /// generation. Does not clobber a same-or-newer in-flight begin.
    func captureBegin(_ snapshot: LiveConnectionSnapshot) -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        let target = max(snapshot.gap?.generation ?? 0, localObligation)
        if let current = inFlight, current >= target {
            return current
        }
        inFlight = target
        return target
    }

    func isInFlight() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return inFlight != nil
    }

    func acknowledgedGeneration() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return lastAcknowledged
    }

    func inFlightGeneration() -> UInt64? {
        lock.lock()
        defer { lock.unlock() }
        return inFlight
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
