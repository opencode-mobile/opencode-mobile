import Foundation

protocol ConnectionProfileStore: Sendable {
    func activeProfile() async -> ConnectionProfile?
    func saveProfile(_ profile: ConnectionProfile) async
    func addedWorktrees(serverBaseURL: String) async -> [String]
    func addWorktree(serverBaseURL: String, worktree: String) async
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async
}

struct UserDefaultsConnectionProfileStore: ConnectionProfileStore, Sendable {
    private let defaults: UserDefaults
    private let secureCredentialStore: SecureCredentialStore
    private let worktreePrefix = "addedProjectWorktrees."
    private let activeBaseURLKey = "active_profile_base_url"
    private let activeUsernameKey = "active_profile_username"

    init(
        defaults: UserDefaults = .standard,
        secureCredentialStore: SecureCredentialStore = KeychainCredentialStore()
    ) {
        self.defaults = defaults
        self.secureCredentialStore = secureCredentialStore
    }

    func activeProfile() async -> ConnectionProfile? {
        guard let baseURL = defaults.string(forKey: activeBaseURLKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !baseURL.isEmpty
        else {
            return nil
        }
        let normalized = baseURL.hasSuffix("/") ? baseURL : baseURL + "/"
        let username = defaults.string(forKey: activeUsernameKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedUsername = (username?.isEmpty == false) ? username! : ServerConnectionDefaults.username
        let password = secureCredentialStore.readPassword(serverBaseURL: normalized) ?? ""
        return ConnectionProfile(baseURL: normalized, username: resolvedUsername, password: password)
    }

    func saveProfile(_ profile: ConnectionProfile) async {
        let normalized = profile.normalizedBaseURL
        defaults.set(normalized, forKey: activeBaseURLKey)
        let username = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(username.isEmpty ? ServerConnectionDefaults.username : username, forKey: activeUsernameKey)
        secureCredentialStore.writePassword(serverBaseURL: normalized, password: profile.password)
    }

    func addedWorktrees(serverBaseURL: String) async -> [String] {
        defaults.stringArray(forKey: worktreePrefix + normalizedKey(serverBaseURL)) ?? []
    }

    func addWorktree(serverBaseURL: String, worktree: String) async {
        var current = await addedWorktrees(serverBaseURL: serverBaseURL)
        guard !current.contains(worktree) else { return }
        current.append(worktree)
        await setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: current)
    }

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {
        var seen = Set<String>()
        let ordered = worktrees.filter { seen.insert($0).inserted }
        defaults.set(ordered, forKey: worktreePrefix + normalizedKey(serverBaseURL))
    }

    private func normalizedKey(_ serverBaseURL: String) -> String {
        serverBaseURL.hasSuffix("/") ? serverBaseURL : serverBaseURL + "/"
    }
}
