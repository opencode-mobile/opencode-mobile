import Foundation

nonisolated final class SseEventDeduper: @unchecked Sendable {
    private let lock = NSLock()
    private var order: [String] = []
    private var seen: Set<String> = []
    private let capacity: Int

    init(capacity: Int = 256) {
        self.capacity = capacity
    }

    func accept(_ id: String?) -> Bool {
        guard let id, !id.isEmpty else { return true }
        lock.lock()
        defer { lock.unlock() }
        if seen.contains(id) { return false }
        seen.insert(id)
        order.append(id)
        if order.count > capacity {
            seen.remove(order.removeFirst())
        }
        return true
    }

    func clear() {
        lock.lock()
        order.removeAll()
        seen.removeAll()
        lock.unlock()
    }
}
