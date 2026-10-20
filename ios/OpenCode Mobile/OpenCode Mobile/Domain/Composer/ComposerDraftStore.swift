import Foundation

struct ComposerDraft: Equatable, Sendable, Codable {
    var text: String
    var agentMentions: [AgentMention]
    var fileMentions: [FileMention]
    var savedAtEpochMs: Int64?

    init(
        text: String,
        agentMentions: [AgentMention] = [],
        fileMentions: [FileMention] = [],
        savedAtEpochMs: Int64? = nil
    ) {
        self.text = text
        self.agentMentions = agentMentions
        self.fileMentions = fileMentions
        self.savedAtEpochMs = savedAtEpochMs
    }

    private enum CodingKeys: String, CodingKey {
        case text, agentMentions, fileMentions, savedAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        text = try container.decode(String.self, forKey: .text)
        agentMentions = try container.decodeIfPresent([AgentMention].self, forKey: .agentMentions) ?? []
        fileMentions = try container.decodeIfPresent([FileMention].self, forKey: .fileMentions) ?? []
        savedAtEpochMs = try container.decodeIfPresent(Int64.self, forKey: .savedAtEpochMs)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(text, forKey: .text)
        try container.encode(agentMentions, forKey: .agentMentions)
        try container.encode(fileMentions, forKey: .fileMentions)
        try container.encodeIfPresent(savedAtEpochMs, forKey: .savedAtEpochMs)
    }
}

protocol ComposerDraftStore: Sendable {
    func load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> ComposerDraft?
    func save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        draft: ComposerDraft
    )
    func delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    )
    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    )
    /// Every draft of one connection (profile removal).
    func clearConnection(connectionIdentity: ModelSelectionConnectionIdentity)
    func clearAll()
}

extension ComposerDraftStore {
    func clearConnection(connectionIdentity _: ModelSelectionConnectionIdentity) {}
}

struct UserDefaultsComposerDraftStore: ComposerDraftStore, @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> ComposerDraft? {
        decode(defaults.data(forKey: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID)))
    }

    func save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        draft: ComposerDraft
    ) {
        saveIfNewer(withTimestamp(draft), key: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID))
        evictOldestSessions(connectionIdentity)
    }

    func delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) {
        defaults.removeObject(forKey: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) {
        let sessionSuffix = "|\(encoded(sessionID))"
        defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(sessionPrefix(connectionIdentity)) && $0.hasSuffix(sessionSuffix) }
            .forEach(defaults.removeObject)
    }

    func clearAll() {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(Self.versionPrefix) }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    func clearConnection(connectionIdentity: ModelSelectionConnectionIdentity) {
        let identityMarker = "|\(identityKey(connectionIdentity))|"
        defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(Self.versionPrefix) && $0.contains(identityMarker) }
            .forEach(defaults.removeObject)
    }

    private func sessionKey(
        _ identity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> String {
        sessionPrefix(identity) + [encoded(worktree), encoded(sessionID)].joined(separator: "|")
    }

    private func sessionPrefix(_ identity: ModelSelectionConnectionIdentity) -> String {
        "\(Self.versionPrefix)|session|\(identityKey(identity))|"
    }

    private func identityKey(_ identity: ModelSelectionConnectionIdentity) -> String {
        [encoded(normalizedBaseURL(identity.serverBaseURL)), encoded(effectiveUsername(identity.username))]
            .joined(separator: "|")
    }

    private func encoded(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private func decode(_ data: Data?) -> ComposerDraft? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(ComposerDraft.self, from: data)
    }

    private func saveIfNewer(_ draft: ComposerDraft, key: String) {
        if let existing = decode(defaults.data(forKey: key)),
           let existingTime = existing.savedAtEpochMs,
           let incomingTime = draft.savedAtEpochMs,
           existingTime > incomingTime {
            return
        }
        guard let data = try? JSONEncoder().encode(draft) else { return }
        defaults.set(data, forKey: key)
    }

    private func evictOldestSessions(_ identity: ModelSelectionConnectionIdentity) {
        let sessionPrefix = sessionPrefix(identity)
        let entries = defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(sessionPrefix) }
            .compactMap { key -> (String, Int64)? in
                guard let draft = decode(defaults.data(forKey: key)) else { return nil }
                return (key, draft.savedAtEpochMs ?? .min)
            }
            .sorted { $0.1 < $1.1 }
        for (key, _) in entries.prefix(max(0, entries.count - Self.maxSessionEntries)) {
            defaults.removeObject(forKey: key)
        }
    }

    private func withTimestamp(_ draft: ComposerDraft) -> ComposerDraft {
        guard draft.savedAtEpochMs == nil else { return draft }
        var timestamped = draft
        timestamped.savedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1_000)
        return timestamped
    }

    private func effectiveUsername(_ username: String) -> String {
        let trimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultUsername : trimmed
    }

    private func normalizedBaseURL(_ serverBaseURL: String) -> String {
        let trimmed = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
    }

    private static let versionPrefix = "composerDraft.v1"
    private static let defaultUsername = "opencode"
    private static let maxSessionEntries = 500
}
