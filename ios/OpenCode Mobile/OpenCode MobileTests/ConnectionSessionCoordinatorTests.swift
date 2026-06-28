import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ConnectionSessionCoordinatorTests {
    @Test @MainActor func clearConnectionData_clearsModelSelectionCache() async {
        let store = InMemorySessionModelSelectionStore()
        await store.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil)
        )
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: InMemoryConnectionProfileStore(),
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: store
        )

        await coordinator.clearConnectionData()

        #expect(await store.load(serverBaseURL: "http://127.0.0.1:4096/", worktree: "/repo", sessionID: "ses-1") == nil)
    }
}

private final class InMemorySessionModelSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private var saved: [String: SessionModelSelection] = [:]

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? {
        saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)]
    }

    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {
        saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)] = selection
    }

    func clearAll() async {
        saved.removeAll()
    }

    private func key(serverBaseURL: String, worktree: String, sessionID: String) -> String {
        "\(serverBaseURL)|\(worktree)|\(sessionID)"
    }
}

private final class InMemoryConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    func activeProfile() async -> ConnectionProfile? { nil }

    func saveProfile(_ profile: ConnectionProfile) async {}

    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }

    func addWorktree(serverBaseURL: String, worktree: String) async {}

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}

    func clearAll() async {}
}

private final class InMemorySecureCredentialStore: SecureCredentialStore, @unchecked Sendable {
    func readPassword(serverBaseURL: String) -> String? { nil }

    func writePassword(serverBaseURL: String, password: String) {}

    func deletePassword(serverBaseURL: String) {}

    func clearAll() {}
}
