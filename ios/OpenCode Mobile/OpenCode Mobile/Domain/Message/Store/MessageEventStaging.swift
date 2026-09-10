import Foundation

/// Per-observation SSE staging capacity between subscribe and store apply.
nonisolated let messageEventStagingCapacity = 4_096

/// Bounded staging between foreground events and `MessageSync` apply.
/// Overflow never drops accepted deltas silently: it marks recovery required.
/// Uses a ring buffer so FIFO dequeue is O(1).
///
/// Continuations are always resumed **after** releasing `lock` so a synchronous
/// resume cannot re-enter locked methods and deadlock.
nonisolated final class MessageEventStaging: @unchecked Sendable {
    enum Item: Sendable {
        case event(OpencodeEvent, byteCost: Int)
        case overflow
        case recoveryRequired
        case evaluate(String)
    }

    private let lock = NSLock()
    private let capacity: Int
    private let byteCapacity: Int
    private var buffer: [Item?]
    private var head = 0
    private var count = 0
    private var queuedBytes = 0
    private var waiters: [CheckedContinuation<Item?, Never>] = []
    private(set) var health: MessageSyncHealth = .healthy
    private(set) var highWater = 0
    private(set) var highWaterBytes = 0
    private var isFinished = false
    private var authorityWindowOpen = false
    private var racedDuringAuthorityWindow = false
    private var pendingRecoveryWhileIdle = false
    private var pendingEvaluate: String?

    var needsAuthoritativeRecovery: Bool {
        lock.lock()
        defer { lock.unlock() }
        return health.requiresAuthoritativeRecovery
    }

    var currentHealth: MessageSyncHealth {
        lock.lock()
        defer { lock.unlock() }
        return health
    }

    init(
        capacity: Int = messageEventStagingCapacity,
        byteCapacity: Int = messageEventStagingByteCapacity
    ) {
        self.capacity = max(1, capacity)
        self.byteCapacity = max(64, byteCapacity)
        buffer = Array(repeating: nil, count: self.capacity)
    }

    func enqueue(_ event: OpencodeEvent, sessionID: String) {
        guard MessageEventRouter.shouldStageForMessageSync(event) else { return }
        let resume: (CheckedContinuation<Item?, Never>, Item)?
        lock.lock()
        if isFinished {
            lock.unlock()
            return
        }
        let relevant = isRelevant(event, sessionID: sessionID)
        if health.requiresAuthoritativeRecovery {
            if relevant {
                _ = noteRelevantEventWhileRecoveringLocked()
                if !MessageEventRouter.isIncrementalTranscriptEvent(event) {
                    lock.unlock()
                    return
                }
            } else {
                lock.unlock()
                return
            }
        }
        let cost = MessageEventRouter.estimatedByteCost(event)
        if count >= capacity || queuedBytes + cost > byteCapacity {
            resume = enterRecoveringLocked(.overflow, wakeConsumer: true)
            lock.unlock()
            resumeWaiter(resume)
            return
        }
        resume = deliverLocked(.event(event, byteCost: cost))
        lock.unlock()
        resumeWaiter(resume)
    }

    func dequeue() async -> Item? {
        lock.lock()
        if let reason = pendingEvaluate {
            pendingEvaluate = nil
            lock.unlock()
            return .evaluate(reason)
        }
        if count > 0 {
            let item = popFrontLocked()
            lock.unlock()
            return item
        }
        if isFinished {
            lock.unlock()
            return nil
        }
        if health.requiresAuthoritativeRecovery || pendingRecoveryWhileIdle {
            pendingRecoveryWhileIdle = false
            lock.unlock()
            return .recoveryRequired
        }
        return await withCheckedContinuation { continuation in
            waiters.append(continuation)
            lock.unlock()
        }
    }

    func drain() {
        lock.lock()
        defer { lock.unlock() }
        while count > 0 {
            _ = popFrontLocked()
        }
    }

    func takeQueuedIncrementalEvents() -> [OpencodeEvent] {
        lock.lock()
        defer { lock.unlock() }
        var events: [OpencodeEvent] = []
        var kept: [Item] = []
        while count > 0 {
            let item = popFrontLocked()
            switch item {
            case .event(let event, _) where MessageEventRouter.isIncrementalTranscriptEvent(event):
                events.append(event)
            case .event, .evaluate:
                kept.append(item)
            case .overflow, .recoveryRequired:
                break
            }
        }
        for item in kept {
            pushBackLocked(item)
        }
        return events
    }

    func enterRecovering(_ reason: MessageSyncRecoveryReason, wakeConsumer: Bool = true) {
        lock.lock()
        let resume = enterRecoveringLocked(reason, wakeConsumer: wakeConsumer)
        lock.unlock()
        resumeWaiter(resume)
    }

    func enterStale(_ reason: MessageSyncRecoveryReason) {
        lock.lock()
        defer { lock.unlock() }
        health = .stale(reason)
        while count > 0 {
            _ = popFrontLocked()
        }
    }

    func requestEvaluate(reason: String) {
        lock.lock()
        guard !isFinished else {
            lock.unlock()
            return
        }
        pendingEvaluate = reason
        let resume: (CheckedContinuation<Item?, Never>, Item)?
        if !waiters.isEmpty {
            pendingEvaluate = nil
            let waiter = waiters.removeFirst()
            resume = (waiter, .evaluate(reason))
        } else {
            resume = nil
        }
        lock.unlock()
        resumeWaiter(resume)
    }

    /// Clears a Recovering latch when authority cannot run for this generation.
    func clearRecoveringLatch() {
        lock.lock()
        defer { lock.unlock() }
        if case .recovering = health {
            health = .healthy
        }
        pendingRecoveryWhileIdle = false
    }

    func beginAuthorityWindow() {
        lock.lock()
        defer { lock.unlock() }
        authorityWindowOpen = true
        racedDuringAuthorityWindow = false
        MessagePipelineProbe.emit(.authorityWindowOpened)
    }

    @discardableResult
    func completeAuthorityWindow(_ outcome: AuthorityWindowOutcome) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        authorityWindowOpen = false
        let raced = racedDuringAuthorityWindow
        racedDuringAuthorityWindow = false
        MessagePipelineProbe.emit(.authorityWindowClosed)
        switch outcome {
        case .failed:
            health = .recovering(.reloadFailed)
            MessagePipelineProbe.emit(.recoveryRetry)
            return false
        case .discarded:
            health = .recovering(.racedAuthority)
            MessagePipelineProbe.emit(.recoveryRetry)
            return false
        case .accepted:
            if raced {
                health = .recovering(.racedAuthority)
                MessagePipelineProbe.emit(.recoveryRetry)
                return false
            }
            health = .healthy
            pendingRecoveryWhileIdle = false
            MessagePipelineProbe.emit(.recoveryHealthy)
            return true
        }
    }

    func noteRelevantEventWhileRecovering() {
        lock.lock()
        let resume = noteRelevantEventWhileRecoveringLocked()
        lock.unlock()
        resumeWaiter(resume)
    }

    func finish() {
        lock.lock()
        isFinished = true
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        for waiter in pending {
            waiter.resume(returning: nil)
        }
    }

    @discardableResult
    private func noteRelevantEventWhileRecoveringLocked() -> (CheckedContinuation<Item?, Never>, Item)? {
        if authorityWindowOpen {
            racedDuringAuthorityWindow = true
            return nil
        }
        pendingRecoveryWhileIdle = true
        if case .recovering = health {
            // Keep current recovering reason.
        } else {
            health = .recovering(.racedAuthority)
        }
        return wakeForRecoveryIfNeededLocked()
    }

    @discardableResult
    private func enterRecoveringLocked(
        _ reason: MessageSyncRecoveryReason,
        wakeConsumer: Bool
    ) -> (CheckedContinuation<Item?, Never>, Item)? {
        health = .recovering(reason)
        while count > 0 {
            _ = popFrontLocked()
        }
        MessagePipelineProbe.emit(.recoveryStarted)
        guard wakeConsumer else { return nil }
        return deliverLocked(.overflow)
    }

    private func wakeForRecoveryIfNeededLocked() -> (CheckedContinuation<Item?, Never>, Item)? {
        guard !waiters.isEmpty else { return nil }
        let waiter = waiters.removeFirst()
        return (waiter, .recoveryRequired)
    }

    private func isRelevant(_ event: OpencodeEvent, sessionID: String) -> Bool {
        switch event.type {
        case "stream.reconnected", "server.connected", "sse.parse.failed":
            return true
        default:
            return event.sessionID == sessionID
        }
    }

    /// Delivers under lock. Returns a waiter to resume **after** unlock.
    private func deliverLocked(_ item: Item) -> (CheckedContinuation<Item?, Never>, Item)? {
        if case .event(_, let cost) = item {
            let depth = count + (waiters.isEmpty ? 1 : 0)
            highWater = max(highWater, depth)
            highWaterBytes = max(highWaterBytes, queuedBytes + cost)
        }
        if !waiters.isEmpty {
            let waiter = waiters.removeFirst()
            return (waiter, item)
        }
        pushBackLocked(item)
        if case .event(_, let cost) = item {
            highWater = max(highWater, count)
            highWaterBytes = max(highWaterBytes, queuedBytes)
            _ = cost
        }
        return nil
    }

    private func resumeWaiter(_ resume: (CheckedContinuation<Item?, Never>, Item)?) {
        guard let (waiter, item) = resume else { return }
        waiter.resume(returning: item)
    }

    private func pushBackLocked(_ item: Item) {
        let index = (head + count) % buffer.count
        buffer[index] = item
        count += 1
        if case .event(_, let cost) = item {
            queuedBytes += cost
        }
    }

    private func popFrontLocked() -> Item {
        let item = buffer[head]!
        buffer[head] = nil
        head = (head + 1) % buffer.count
        count -= 1
        if case .event(_, let cost) = item {
            queuedBytes = max(0, queuedBytes - cost)
        }
        return item
    }
}
