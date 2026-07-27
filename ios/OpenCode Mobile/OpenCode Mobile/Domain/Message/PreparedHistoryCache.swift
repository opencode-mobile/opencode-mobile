import Foundation
import OSLog

/// iOS warm prepared-history retention budgets (Phase 5).
enum PreparedHistoryCacheLimits {
    nonisolated static let characterBudget = 512_000
    nonisolated static let sessionEntryCap = 8
}

struct PreparedHistoryCacheKey: Hashable, Sendable {
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
@MainActor
final class PreparedHistoryCache {
    private struct Entry {
        var messagesByID: [String: DisplayMessage]
        var characterCost: Int
        var lastUsedAt: ContinuousClock.Instant
    }

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
        guard var entry = entries[key] else { return nil }
        entry.lastUsedAt = clock.now
        entries[key] = entry
        return entry.messagesByID
    }

    func setMessages(_ messagesByID: [String: DisplayMessage], for key: PreparedHistoryCacheKey) {
        let cost = messagesByID.values.reduce(0) { $0 + $1.text.count }
        entries[key] = Entry(
            messagesByID: messagesByID,
            characterCost: cost,
            lastUsedAt: clock.now
        )
        evictIfNeeded(reason: "commit")
    }

    func removeSession(sessionID: String) {
        let matching = entries.keys.filter { $0.sessionID == sessionID }
        guard !matching.isEmpty else { return }
        for key in matching {
            entries.removeValue(forKey: key)
        }
        OpenCodeLog.messageContent.info(
            "preparedCacheEvict reason=session-delete session=\(sessionID, privacy: .public) count=\(matching.count)"
        )
    }

    func removeAll(reason: String) {
        let count = entries.count
        entries.removeAll()
        if count > 0 {
            OpenCodeLog.messageContent.info(
                "preparedCacheEvict reason=\(reason, privacy: .public) count=\(count)"
            )
        }
    }

    func handleMemoryPressure() {
        removeAll(reason: "memory-pressure")
    }

    var entryCount: Int { entries.count }

    var totalCharacterCost: Int {
        entries.values.reduce(0) { $0 + $1.characterCost }
    }

    private func evictIfNeeded(reason: String) {
        while entries.count > sessionEntryCap || totalCharacterCost > characterBudget {
            guard let victim = entries.min(by: { $0.value.lastUsedAt < $1.value.lastUsedAt }) else {
                return
            }
            entries.removeValue(forKey: victim.key)
            OpenCodeLog.messageContent.info(
                "preparedCacheEvict reason=\(reason, privacy: .public) session=\(victim.key.sessionID, privacy: .public) cost=\(victim.value.characterCost)"
            )
        }
    }
}
