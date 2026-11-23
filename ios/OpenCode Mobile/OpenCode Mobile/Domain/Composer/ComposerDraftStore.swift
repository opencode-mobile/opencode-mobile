import Foundation

struct ComposerDraft: Equatable, Sendable, Codable {
    var text: String
    var agentMentions: [AgentMention]
    var fileMentions: [FileMention]
    var skillMentions: [SkillMention]
    var savedAtEpochMs: Int64?

    init(
        text: String,
        agentMentions: [AgentMention] = [],
        fileMentions: [FileMention] = [],
        skillMentions: [SkillMention] = [],
        savedAtEpochMs: Int64? = nil
    ) {
        self.text = text
        self.agentMentions = agentMentions
        self.fileMentions = fileMentions
        self.skillMentions = skillMentions
        self.savedAtEpochMs = savedAtEpochMs
    }

    private enum CodingKeys: String, CodingKey {
        case text, agentMentions, fileMentions, skillMentions, savedAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        text = try container.decode(String.self, forKey: .text)
        agentMentions = try container.decodeIfPresent([AgentMention].self, forKey: .agentMentions) ?? []
        fileMentions = try container.decodeIfPresent([FileMention].self, forKey: .fileMentions) ?? []
        skillMentions = try container.decodeIfPresent([SkillMention].self, forKey: .skillMentions) ?? []
        savedAtEpochMs = try container.decodeIfPresent(Int64.self, forKey: .savedAtEpochMs)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(text, forKey: .text)
        try container.encode(agentMentions, forKey: .agentMentions)
        try container.encode(fileMentions, forKey: .fileMentions)
        try container.encode(skillMentions, forKey: .skillMentions)
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
        let key = sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID)
        ConnectionIdentityKeys.migrate(
            defaults,
            to: key,
            from: legacySessionKeys(connectionIdentity, worktree: worktree, sessionID: sessionID),
            savedAt: { decode($0)?.savedAtEpochMs }
        )
        return decode(defaults.data(forKey: key))
    }

    func save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        draft: ComposerDraft
    ) {
        _ = load(connectionIdentity: connectionIdentity, worktree: worktree, sessionID: sessionID)
        saveIfNewer(withTimestamp(draft), key: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID))
        evictOldestSessions(connectionIdentity)
    }

    func delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) {
        defaults.removeObject(forKey: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID))
        legacySessionKeys(connectionIdentity, worktree: worktree, sessionID: sessionID)
            .forEach(defaults.removeObject)
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) {
        let sessionSuffix = "|\(encoded(sessionID))"
        let prefixes = ([ConnectionIdentityKeys.current(connectionIdentity)]
            + ConnectionIdentityKeys.legacy(connectionIdentity)).map { "\(Self.versionPrefix)|session|\($0)|" }
        defaults.dictionaryRepresentation().keys
            .filter { key in prefixes.contains { key.hasPrefix($0) } && key.hasSuffix(sessionSuffix) }
            .forEach(defaults.removeObject)
    }

    func clearAll() {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(Self.versionPrefix) }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    func clearConnection(connectionIdentity: ModelSelectionConnectionIdentity) {
        let markers = ([ConnectionIdentityKeys.current(connectionIdentity)]
            + ConnectionIdentityKeys.legacy(connectionIdentity)).map { "|\($0)|" }
        defaults.dictionaryRepresentation().keys
            .filter { key in key.hasPrefix(Self.versionPrefix) && markers.contains { key.contains($0) } }
            .forEach(defaults.removeObject)
    }

    private func legacySessionKeys(
        _ identity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> [String] {
        ConnectionIdentityKeys.legacy(identity).map {
            "\(Self.versionPrefix)|session|\($0)|" + [encoded(worktree), encoded(sessionID)].joined(separator: "|")
        }
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
        ConnectionIdentityKeys.current(identity)
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

    private static let versionPrefix = "composerDraft.v1"
    private static let maxSessionEntries = 500
}
