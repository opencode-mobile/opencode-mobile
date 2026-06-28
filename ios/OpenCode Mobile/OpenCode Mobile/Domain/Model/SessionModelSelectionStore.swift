import Foundation

struct SessionModelSelection: Equatable, Sendable, Codable {
    let providerID: String
    let modelID: String
    let variant: String?
}

protocol SessionModelSelectionStore: Sendable {
    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection?
    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async
    func clearAll() async
}

struct UserDefaultsSessionModelSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let prefix = "sessionModelSelection."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? {
        guard let data = defaults.data(forKey: cacheKey(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)) else {
            return nil
        }
        return try? JSONDecoder().decode(SessionModelSelection.self, from: data)
    }

    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {
        guard let data = try? JSONEncoder().encode(selection) else { return }
        defaults.set(data, forKey: cacheKey(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID))
    }

    func clearAll() async {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefix) }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    private func cacheKey(serverBaseURL: String, worktree: String, sessionID: String) -> String {
        prefix + [normalizedBaseURL(serverBaseURL), worktree, sessionID].joined(separator: "|")
    }

    private func normalizedBaseURL(_ serverBaseURL: String) -> String {
        let trimmed = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
    }
}
