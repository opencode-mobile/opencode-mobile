import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ConnectionSessionCoordinatorTests {
    @Test @MainActor func clearConnectionData_clearsModelSelectionCache() async {
        let store = InMemorySessionModelSelectionStore()
        let draftStore = InMemoryComposerDraftStore()
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
            sessionModelSelectionStore: store,
            composerDraftStore: draftStore
        )
        await draftStore.save(
            connectionIdentity: ModelSelectionConnectionIdentity(
                serverBaseURL: "http://127.0.0.1:4096/",
                username: "opencode"
            ),
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "draft")
        )

        await coordinator.clearConnectionData()

        #expect(await store.load(serverBaseURL: "http://127.0.0.1:4096/", worktree: "/repo", sessionID: "ses-1") == nil)
        #expect(
            await draftStore.load(
                connectionIdentity: ModelSelectionConnectionIdentity(
                    serverBaseURL: "http://127.0.0.1:4096/",
                    username: "opencode"
                ),
                worktree: "/repo",
                sessionID: "ses-1"
            ) == nil
        )
    }
}

private final class InMemoryComposerDraftStore: ComposerDraftStore, @unchecked Sendable {
    private var saved: [String: ComposerDraft] = [:]

    func load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> ComposerDraft? {
        saved["\(connectionIdentity.serverBaseURL)|\(worktree)|\(sessionID)"]
    }

    func save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        draft: ComposerDraft
    ) async {
        saved["\(connectionIdentity.serverBaseURL)|\(worktree)|\(sessionID)"] = draft
    }

    func delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        saved.removeValue(forKey: "\(connectionIdentity.serverBaseURL)|\(worktree)|\(sessionID)")
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        saved.keys
            .filter { $0.hasPrefix(connectionIdentity.serverBaseURL) && $0.hasSuffix("|\(sessionID)") }
            .forEach { saved.removeValue(forKey: $0) }
    }

    func clearAll() async {
        saved.removeAll()
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

    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection? {
        await load(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID)
    }

    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        await save(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID, selection: selection)
    }

    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        saved.removeValue(forKey: key(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        saved.keys
            .filter { $0.hasPrefix(connectionIdentity.serverBaseURL) && $0.hasSuffix("|\(sessionID)") }
            .forEach { saved.removeValue(forKey: $0) }
    }

    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection? { nil }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {}

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

    func removeWorktree(serverBaseURL: String, worktree: String) async {}

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}

    func clearAll() async {}
}

private final class InMemorySecureCredentialStore: SecureCredentialStore, @unchecked Sendable {
    func readPassword(serverBaseURL: String) -> String? { nil }

    func writePassword(serverBaseURL: String, password: String) {}

    func deletePassword(serverBaseURL: String) {}

    func clearAll() {}
}
