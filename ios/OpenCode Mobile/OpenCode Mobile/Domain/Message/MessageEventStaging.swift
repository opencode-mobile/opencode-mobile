import Foundation

/// Per-observation SSE staging capacity between subscribe and store apply.
nonisolated let messageEventStagingCapacity = 4_096

/// Bounded staging between foreground events and `MessageSync` apply.
/// Overflow never drops accepted deltas silently: it marks recovery required.
@MainActor
final class MessageEventStaging {
    enum Item: Sendable {
        case event(OpencodeEvent)
        case overflow
    }

    private let capacity: Int
    private var queue: [Item] = []
    private var waiters: [CheckedContinuation<Item?, Never>] = []
    private(set) var needsAuthoritativeRecovery = false
    private(set) var highWater = 0
    private var isFinished = false

    init(capacity: Int = messageEventStagingCapacity) {
        self.capacity = max(1, capacity)
    }

    func enqueue(_ event: OpencodeEvent) {
        guard !isFinished else { return }
        guard !needsAuthoritativeRecovery else { return }
        if queue.count >= capacity {
            markOverflow()
            return
        }
        deliver(.event(event))
    }

    func dequeue() async -> Item? {
        if !queue.isEmpty {
            return queue.removeFirst()
        }
        if isFinished || needsAuthoritativeRecovery {
            return needsAuthoritativeRecovery ? .overflow : nil
        }
        return await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func drain() {
        queue.removeAll(keepingCapacity: false)
    }

    func clearRecoveryFlag() {
        needsAuthoritativeRecovery = false
    }

    func finish() {
        isFinished = true
        let pending = waiters
        waiters.removeAll()
        for waiter in pending {
            waiter.resume(returning: nil)
        }
    }

    private func markOverflow() {
        guard !needsAuthoritativeRecovery else { return }
        needsAuthoritativeRecovery = true
        queue.removeAll(keepingCapacity: false)
        deliver(.overflow)
    }

    private func deliver(_ item: Item) {
        if case .event = item {
            // Overflow markers are not counted toward high-water depth.
            let depth = queue.count + (waiters.isEmpty ? 1 : 0)
            highWater = max(highWater, depth)
        }
        if let waiter = waiters.first {
            waiters.removeFirst()
            waiter.resume(returning: item)
            return
        }
        queue.append(item)
        if case .event = item {
            highWater = max(highWater, queue.count)
        }
    }
}
