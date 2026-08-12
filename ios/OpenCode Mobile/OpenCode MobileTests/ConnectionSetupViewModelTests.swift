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
        viewModel.baseURL = "http://127.0.0.1:5000/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSave == false)
    }
}

private struct FakeConnectionProbeService: ConnectionProbing {
    let result: Result<ServerProtocol, Error>

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        try result.get()
    }
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
