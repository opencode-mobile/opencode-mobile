import Foundation

/// Per-observation SSE staging capacity between subscribe and store apply.
nonisolated let messageEventStagingCapacity = 4_096

/// Bounded staging between foreground events and `MessageSync` apply.
/// Overflow never drops accepted deltas silently: it marks recovery required.
/// Uses a ring buffer so FIFO dequeue is O(1).
nonisolated final class MessageEventStaging: @unchecked Sendable {
    enum Item: Sendable {
        case event(OpencodeEvent, epoch: UInt64, byteCost: Int)
        case overflow
        case recoveryRequired
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

    func enqueue(_ event: OpencodeEvent, sessionID: String, epoch: UInt64) {
        guard MessageEventRouter.shouldStageForMessageSync(event) else { return }
        lock.lock()
        defer { lock.unlock() }
        guard !isFinished else { return }
        let relevant = isRelevant(event, sessionID: sessionID)
        if health.requiresAuthoritativeRecovery {
            if relevant {
                noteRelevantEventWhileRecoveringLocked()
            }
            return
        }
        let cost = MessageEventRouter.estimatedByteCost(event)
        if count >= capacity || queuedBytes + cost > byteCapacity {
            enterRecoveringLocked(.overflow, wakeConsumer: true)
            return
        }
        deliverLocked(.event(event, epoch: epoch, byteCost: cost))
    }

    func dequeue() async -> Item? {
        lock.lock()
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

    func enterRecovering(_ reason: MessageSyncRecoveryReason, wakeConsumer: Bool = true) {
        lock.lock()
        defer { lock.unlock() }
        enterRecoveringLocked(reason, wakeConsumer: wakeConsumer)
    }

    func enterStale(_ reason: MessageSyncRecoveryReason) {
        lock.lock()
        defer { lock.unlock() }
        health = .stale(reason)
        while count > 0 {
            _ = popFrontLocked()
        }
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
    func completeAuthorityWindow(reloadSucceeded: Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        authorityWindowOpen = false
        let raced = racedDuringAuthorityWindow
        racedDuringAuthorityWindow = false
        MessagePipelineProbe.emit(.authorityWindowClosed)
        guard reloadSucceeded else {
            health = .recovering(.reloadFailed)
            MessagePipelineProbe.emit(.recoveryRetry)
            return false
        }
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

    func noteRelevantEventWhileRecovering() {
        lock.lock()
        defer { lock.unlock() }
        noteRelevantEventWhileRecoveringLocked()
    }

    func finish() {
        lock.lock()
        defer { lock.unlock() }
        isFinished = true
        let pending = waiters
        waiters.removeAll()
        for waiter in pending {
            waiter.resume(returning: nil)
        }
    }

    private func noteRelevantEventWhileRecoveringLocked() {
        if authorityWindowOpen {
            racedDuringAuthorityWindow = true
        } else {
            pendingRecoveryWhileIdle = true
            if case .recovering = health {
                // Keep current recovering reason.
            } else {
                health = .recovering(.racedAuthority)
            }
            wakeForRecoveryIfNeededLocked()
        }
    }

    private func enterRecoveringLocked(_ reason: MessageSyncRecoveryReason, wakeConsumer: Bool) {
        health = .recovering(reason)
        while count > 0 {
            _ = popFrontLocked()
        }
        MessagePipelineProbe.emit(.recoveryStarted)
        if wakeConsumer {
            deliverLocked(.overflow)
        }
    }

    private func wakeForRecoveryIfNeededLocked() {
        guard !waiters.isEmpty else { return }
        let waiter = waiters.removeFirst()
        waiter.resume(returning: .recoveryRequired)
    }

    private func isRelevant(_ event: OpencodeEvent, sessionID: String) -> Bool {
        switch event.type {
        case "stream.reconnected", "server.connected":
            return true
        default:
            return event.sessionID == sessionID
        }
    }

    private func deliverLocked(_ item: Item) {
        if case .event(_, _, let cost) = item {
            let depth = count + (waiters.isEmpty ? 1 : 0)
            highWater = max(highWater, depth)
            highWaterBytes = max(highWaterBytes, queuedBytes + cost)
        }
        if !waiters.isEmpty {
            let waiter = waiters.removeFirst()
            waiter.resume(returning: item)
            return
        }
        pushBackLocked(item)
        if case .event(_, _, let cost) = item {
            highWater = max(highWater, count)
            highWaterBytes = max(highWaterBytes, queuedBytes)
            _ = cost
        }
    }

    private func pushBackLocked(_ item: Item) {
        let index = (head + count) % buffer.count
        buffer[index] = item
        count += 1
        if case .event(_, _, let cost) = item {
            queuedBytes += cost
        }
    }

    private func popFrontLocked() -> Item {
        let item = buffer[head]!
        buffer[head] = nil
        head = (head + 1) % buffer.count
        count -= 1
        if case .event(_, _, let cost) = item {
            queuedBytes = max(0, queuedBytes - cost)
        }
        return item
    }
}
