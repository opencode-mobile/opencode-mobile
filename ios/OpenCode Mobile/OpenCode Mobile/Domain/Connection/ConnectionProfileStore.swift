import Foundation

protocol ConnectionProfileStore: Sendable {
    func addedWorktrees(serverBaseURL: String) async -> [String]
    func addWorktree(serverBaseURL: String, worktree: String) async
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async
}

struct UserDefaultsConnectionProfileStore: ConnectionProfileStore, Sendable {
    private let defaults: UserDefaults
    private let prefix = "addedProjectWorktrees."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func addedWorktrees(serverBaseURL: String) async -> [String] {
        defaults.stringArray(forKey: prefix + serverBaseURL) ?? []
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
        defaults.set(ordered, forKey: prefix + serverBaseURL)
    }
}
