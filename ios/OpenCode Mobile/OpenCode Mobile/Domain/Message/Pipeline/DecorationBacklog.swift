import Foundation

/// Bounded pending decoration work for Phase 4/5 lanes.
/// Text-lane publishes are never gated by this queue.
nonisolated enum DecorationBacklogPolicy {
    static let pendingCountCapacity = 128
    static let pendingCharCostBudget = 512_000
}

nonisolated struct DecorationJob: Sendable {
    let generation: UInt64
    let sessionKey: String
    let messageID: String
    let blockID: String
    let contentRevision: ContentRevision
    /// Plain source for the keyed block (must match `contentRevision`).
    let plainText: String
    let isStreaming: Bool
    let messages: [Message]
    /// Lowest number is dropped first under pressure.
    /// 0 = remaining history, 1 = viewport enrich, 2 = streaming decoration.
    let priority: Int

    var textHash: Int { contentRevision.textHash }
    var textLength: Int { contentRevision.textLength }
    var charCost: Int { textLength }
}

/// Lowest-priority pending decoration is dropped first when bounds are exceeded.
nonisolated final class DecorationBacklog: @unchecked Sendable {
    private let lock = NSLock()
    private var jobs: [DecorationJob] = []
    private var queuedChars = 0
    private var _highWaterCount = 0
    private var _highWaterChars = 0

    var size: Int {
        lock.lock()
        defer { lock.unlock() }
        return jobs.count
    }

    var highWaterCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _highWaterCount
    }

    var highWaterChars: Int {
        lock.lock()
        defer { lock.unlock() }
        return _highWaterChars
    }

    @discardableResult
    func tryEnqueue(_ job: DecorationJob) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        while jobs.count >= DecorationBacklogPolicy.pendingCountCapacity
            || queuedChars + job.charCost > DecorationBacklogPolicy.pendingCharCostBudget
        {
            guard let dropped = dropLowestPriorityUnlocked() else { return false }
            queuedChars = max(0, queuedChars - dropped.charCost)
        }
        jobs.append(job)
        queuedChars += job.charCost
        _highWaterCount = max(_highWaterCount, jobs.count)
        _highWaterChars = max(_highWaterChars, queuedChars)
        return true
    }

    /// Keep only the latest streaming decoration candidate per block.
    @discardableResult
    func replaceStreamingJob(_ job: DecorationJob) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        jobs.removeAll { candidate in
            guard candidate.blockID == job.blockID, candidate.isStreaming else { return false }
            queuedChars = max(0, queuedChars - candidate.charCost)
            return true
        }
        while jobs.count >= DecorationBacklogPolicy.pendingCountCapacity
            || queuedChars + job.charCost > DecorationBacklogPolicy.pendingCharCostBudget
        {
            guard let dropped = dropLowestPriorityUnlocked() else { return false }
            queuedChars = max(0, queuedChars - dropped.charCost)
        }
        jobs.append(job)
        queuedChars += job.charCost
        _highWaterCount = max(_highWaterCount, jobs.count)
        _highWaterChars = max(_highWaterChars, queuedChars)
        return true
    }

    func poll() -> DecorationJob? {
        lock.lock()
        defer { lock.unlock() }
        guard !jobs.isEmpty else { return nil }
        let job = jobs.removeFirst()
        queuedChars = max(0, queuedChars - job.charCost)
        return job
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        jobs.removeAll()
        queuedChars = 0
    }

    private func dropLowestPriorityUnlocked() -> DecorationJob? {
        guard !jobs.isEmpty else { return nil }
        guard let index = jobs.indices.min(by: { jobs[$0].priority < jobs[$1].priority }) else {
            return nil
        }
        return jobs.remove(at: index)
    }
}
