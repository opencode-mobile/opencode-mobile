import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ConnectionSetupViewModelTests {
    @Test func save_disabledUntilTestSucceeds() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            )
        )
        #expect(viewModel.canSave == false)
        let saved = await viewModel.saveConnection()
        #expect(saved == false)
    }

    @Test func fieldChange_resetsSuccessfulTest() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            )
        )
        await viewModel.testConnection()
        #expect(viewModel.canSave == true)
        #expect(viewModel.testResultMessage == "Server reachable · API v1")
        viewModel.baseURL = "http://127.0.0.1:5000/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSave == false)
    }

    @Test func testConnection_successMessageIncludesProtocol() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v2)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            )
        )
        await viewModel.testConnection()
        #expect(viewModel.testResultMessage == "Server reachable · API v2")
    }

    @Test func revertedEdit_keepsSavedProtocol() async {
        let store = RecordingConnectionProfileStore()
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            )
        )
        viewModel.configure(
            initialProfile: ConnectionProfile(
                baseURL: "http://10.0.0.1:4096/",
                username: "opencode",
                password: "secret",
                serverProtocol: .v2,
                id: "home",
                name: "Home"
            ),
            mode: .edit
        )
        #expect(viewModel.canSave == true)

        viewModel.baseURL = "http://10.0.0.9:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSave == false)

        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSave == true)

        let saved = await viewModel.saveConnection()
        #expect(saved == true)
        #expect(store.saved.last?.serverProtocol == .v2)
    }
}

private struct FakeConnectionProbeService: ConnectionProbing {
    let result: Result<ServerProtocol, Error>

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        try result.get()
    }
}

private final class RecordingConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    var saved: [ConnectionProfile] = []

    func activeProfile() async -> ConnectionProfile? { saved.last }
    func saveProfile(_ profile: ConnectionProfile) async throws { saved.append(profile) }
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async { saved.removeAll() }
}

private struct FakeSetupConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}
