import Foundation

/// iOS warm prepared-history retention budgets (Phase 5).
enum PreparedHistoryCacheLimits {
    nonisolated static let characterBudget = 512_000
    nonisolated static let sessionEntryCap = 8
}

nonisolated struct PreparedHistoryCacheKey: Hashable, Sendable {
    let connectionProfileID: String
    let directory: String
    let sessionID: String

    init(
        connectionProfileID: String,
        directory: String?,
        sessionID: String
    ) {
        self.connectionProfileID = connectionProfileID
        self.directory = directory ?? ""
        self.sessionID = sessionID
    }

    nonisolated static func profileID(baseURL: String, username: String) -> String {
        "\(baseURL.lowercased())|\(username.lowercased())"
    }
}

/// Bounded LRU prepared-history cache keyed by profile + directory + session.
/// Lock-guarded so `DisplayMessagePipeline` can orchestrate off MainActor.
nonisolated final class PreparedHistoryCache: @unchecked Sendable {
    private struct Entry {
        var messagesByID: [String: DisplayMessage]
        var characterCost: Int
        var lastUsedAt: ContinuousClock.Instant
    }

    private let lock = NSLock()
    private var entries: [PreparedHistoryCacheKey: Entry] = [:]
    private let characterBudget: Int
    private let sessionEntryCap: Int
    private let clock: ContinuousClock

    init(
        characterBudget: Int = PreparedHistoryCacheLimits.characterBudget,
        sessionEntryCap: Int = PreparedHistoryCacheLimits.sessionEntryCap,
        clock: ContinuousClock = ContinuousClock()
    ) {
        self.characterBudget = characterBudget
        self.sessionEntryCap = sessionEntryCap
        self.clock = clock
    }

    func messages(for key: PreparedHistoryCacheKey) -> [String: DisplayMessage]? {
        lock.lock()
        defer { lock.unlock() }
        guard var entry = entries[key] else { return nil }
        entry.lastUsedAt = clock.now
        entries[key] = entry
        return entry.messagesByID
    }

    func setMessages(_ messagesByID: [String: DisplayMessage], for key: PreparedHistoryCacheKey) {
        lock.lock()
        defer { lock.unlock() }
        let cost = messagesByID.values.reduce(0) { $0 + $1.text.count }
        entries[key] = Entry(
            messagesByID: messagesByID,
            characterCost: cost,
            lastUsedAt: clock.now
        )
        evictIfNeededLocked(reason: "commit")
    }

    func removeSession(sessionID: String) {
        lock.lock()
        defer { lock.unlock() }
        let matching = entries.keys.filter { $0.sessionID == sessionID }
        guard !matching.isEmpty else { return }
        for key in matching {
            entries.removeValue(forKey: key)
        }
        OpenCodeLog.messageContent.info(
            "preparedCacheEvict reason=session-delete session=\(sessionID) count=\(matching.count)"
        )
    }

    func removeAll(reason: String) {
        lock.lock()
        defer { lock.unlock() }
        let count = entries.count
        entries.removeAll()
        if count > 0 {
            OpenCodeLog.messageContent.info(
                "preparedCacheEvict reason=\(reason) count=\(count)"
            )
        }
    }

    func handleMemoryPressure() {
        removeAll(reason: "memory-pressure")
    }

    var entryCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return entries.count
    }

    var totalCharacterCost: Int {
        lock.lock()
        defer { lock.unlock() }
        return totalCharacterCostLocked
    }

    private var totalCharacterCostLocked: Int {
        entries.values.reduce(0) { $0 + $1.characterCost }
    }

    private func evictIfNeededLocked(reason: String) {
        while entries.count > sessionEntryCap || totalCharacterCostLocked > characterBudget {
            guard let victim = entries.min(by: { $0.value.lastUsedAt < $1.value.lastUsedAt }) else {
                return
            }
            entries.removeValue(forKey: victim.key)
            OpenCodeLog.messageContent.info(
                "preparedCacheEvict reason=\(reason) session=\(victim.key.sessionID) cost=\(victim.value.characterCost)"
            )
        }
    }
}
