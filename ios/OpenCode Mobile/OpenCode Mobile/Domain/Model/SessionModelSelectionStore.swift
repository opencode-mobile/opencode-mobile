import Foundation

struct SessionModelSelection: Equatable, Sendable, Codable {
    let providerID: String
    let modelID: String
    let variant: String?
    var agentID: String? = nil
    var savedAtEpochMs: Int64? = nil
}

struct ModelSelectionConnectionIdentity: Equatable, Sendable {
    let serverBaseURL: String
    let username: String
}

protocol SessionModelSelectionStore: Sendable {
    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection?
    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async
    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async
    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async
    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection?
    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async
    /// T3 runtime mode the user last chose for a new thread on this profile + worktree.
    func loadNewSessionRuntimeMode(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> String?
    func saveNewSessionRuntimeMode(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        runtimeMode: String
    ) async
    func clearAll() async
    /// Synchronous reads for a Session Chat's first frame; `nil` when the store only loads asynchronously.
    func peekSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> SessionModelSelection?
    func peekNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) -> SessionModelSelection?
}

extension SessionModelSelectionStore {
    func peekSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> SessionModelSelection? {
        nil
    }

    func peekNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) -> SessionModelSelection? {
        nil
    }
}

extension SessionModelSelectionStore {
    /// Stores without T3 runtime-mode memory start every new thread on the default mode.
    func loadNewSessionRuntimeMode(
        connectionIdentity _: ModelSelectionConnectionIdentity,
        worktree _: String
    ) async -> String? {
        nil
    }

    func saveNewSessionRuntimeMode(
        connectionIdentity _: ModelSelectionConnectionIdentity,
        worktree _: String,
        runtimeMode _: String
    ) async {}
}

struct UserDefaultsSessionModelSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let prefix = "sessionModelSelection."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? {
        await loadSession(
            connectionIdentity: ModelSelectionConnectionIdentity(serverBaseURL: serverBaseURL, username: Self.defaultUsername),
            worktree: worktree,
            sessionID: sessionID
        )
    }

    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {
        await saveSession(
            connectionIdentity: ModelSelectionConnectionIdentity(serverBaseURL: serverBaseURL, username: Self.defaultUsername),
            worktree: worktree,
            sessionID: sessionID,
            selection: selection
        )
    }

    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection? {
        peekSession(connectionIdentity: connectionIdentity, worktree: worktree, sessionID: sessionID)
    }

    func peekSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> SessionModelSelection? {
        decode(defaults.data(forKey: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID)))
            ?? decode(defaults.data(forKey: legacyKey(
                serverBaseURL: connectionIdentity.serverBaseURL,
                worktree: worktree,
                sessionID: sessionID
            )))
    }

    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        let timestamped = withTimestamp(selection)
        saveIfNewer(timestamped, key: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID))
        defaults.removeObject(forKey: legacyKey(
            serverBaseURL: connectionIdentity.serverBaseURL,
            worktree: worktree,
            sessionID: sessionID
        ))
        evictOldestSessions(connectionIdentity)
    }

    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        defaults.removeObject(forKey: sessionKey(connectionIdentity, worktree: worktree, sessionID: sessionID))
        defaults.removeObject(forKey: legacyKey(
            serverBaseURL: connectionIdentity.serverBaseURL,
            worktree: worktree,
            sessionID: sessionID
        ))
    }

    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection? {
        peekNewSessionPreference(connectionIdentity: connectionIdentity, worktree: worktree)
    }

    func peekNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) -> SessionModelSelection? {
        decode(defaults.data(forKey: preferenceKey(connectionIdentity, worktree: worktree)))
    }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {
        saveIfNewer(withTimestamp(selection), key: preferenceKey(connectionIdentity, worktree: worktree))
    }

    func loadNewSessionRuntimeMode(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> String? {
        let stored = defaults.string(forKey: runtimeModeKey(connectionIdentity, worktree: worktree))
        return stored.flatMap { $0.isEmpty ? nil : $0 }
    }

    func saveNewSessionRuntimeMode(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        runtimeMode: String
    ) async {
        defaults.set(runtimeMode, forKey: runtimeModeKey(connectionIdentity, worktree: worktree))
    }

    func clearAll() async {
        let keys = defaults.dictionaryRepresentation().keys.filter {
            $0.hasPrefix(prefix) || $0.hasPrefix(Self.versionPrefix)
        }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        let sessionSuffix = "|\(encoded(sessionID))"
        let legacyPrefix = prefix + normalizedBaseURL(connectionIdentity.serverBaseURL)
        defaults.dictionaryRepresentation().keys
            .filter {
                ($0.hasPrefix(sessionPrefix(connectionIdentity)) && $0.hasSuffix(sessionSuffix)) ||
                    ($0.hasPrefix(legacyPrefix) && $0.hasSuffix("|\(sessionID)"))
            }
            .forEach(defaults.removeObject)
    }

    private func legacyKey(serverBaseURL: String, worktree: String, sessionID: String) -> String {
        prefix + [normalizedBaseURL(serverBaseURL), worktree, sessionID].joined(separator: "|")
    }

    private func sessionKey(
        _ identity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> String {
        sessionPrefix(identity) + [encoded(worktree), encoded(sessionID)].joined(separator: "|")
    }

    private func preferenceKey(_ identity: ModelSelectionConnectionIdentity, worktree: String) -> String {
        preferencePrefix(identity) + encoded(worktree)
    }

    private func runtimeModeKey(_ identity: ModelSelectionConnectionIdentity, worktree: String) -> String {
        "\(Self.versionPrefix)|runtimeMode|\(identityKey(identity))|" + encoded(worktree)
    }

    private func sessionPrefix(_ identity: ModelSelectionConnectionIdentity) -> String {
        "\(Self.versionPrefix)|session|\(identityKey(identity))|"
    }

    private func preferencePrefix(_ identity: ModelSelectionConnectionIdentity) -> String {
        "\(Self.versionPrefix)|preference|\(identityKey(identity))|"
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

    private func decode(_ data: Data?) -> SessionModelSelection? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(SessionModelSelection.self, from: data)
    }

    private func saveIfNewer(_ selection: SessionModelSelection, key: String) {
        if let existing = decode(defaults.data(forKey: key)),
           let existingTime = existing.savedAtEpochMs,
           let incomingTime = selection.savedAtEpochMs,
           existingTime > incomingTime {
            return
        }
        guard let data = try? JSONEncoder().encode(selection) else { return }
        defaults.set(data, forKey: key)
    }

    private func evictOldestSessions(_ identity: ModelSelectionConnectionIdentity) {
        let sessionPrefix = sessionPrefix(identity)
        let entries = defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(sessionPrefix) }
            .compactMap { key -> (String, Int64)? in
                guard let selection = decode(defaults.data(forKey: key)) else { return nil }
                return (key, selection.savedAtEpochMs ?? .min)
            }
            .sorted { $0.1 < $1.1 }
        for (key, _) in entries.prefix(max(0, entries.count - Self.maxSessionEntries)) {
            defaults.removeObject(forKey: key)
        }
    }

    private func withTimestamp(_ selection: SessionModelSelection) -> SessionModelSelection {
        guard selection.savedAtEpochMs == nil else { return selection }
        var timestamped = selection
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

    private static let versionPrefix = "modelSelection.v2"
    private static let defaultUsername = "opencode"
    private static let maxSessionEntries = 500
}
