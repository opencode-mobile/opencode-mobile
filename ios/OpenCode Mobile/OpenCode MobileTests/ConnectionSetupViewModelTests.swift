import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ConnectionSetupViewModelTests {
    @Test func newForm_startsWithEmptyHostAndCollapsedCredentials() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        #expect(viewModel.baseURL.isEmpty)
        #expect(viewModel.primaryActionLabel == "Connect")
        #expect(viewModel.credentialsExpanded == false)
        #expect(ConnectionSetupViewModel.hostIsBlank(viewModel.baseURL))
    }

    @Test func submit_emptyHost_showsSnackbar() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(viewModel.snackbar?.text == "Enter a host")
    }

    @Test func submit_create_probesThenSaves() async {
        let store = RecordingConnectionProfileStore()
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v2)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.password = "secret"
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(store.saved.count == 1)
        #expect(store.saved.last?.serverProtocol == .v2)
    }

    @Test func submit_authFailure_expandsCredentialsAndSnackbars() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(
                result: .failure(ServerProbeError.http(statusCode: 401, message: "Unauthorized"))
            ),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.password = "wrong"
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(viewModel.credentialsExpanded == true)
        #expect(viewModel.requestPasswordFocus == true)
        #expect(viewModel.snackbar?.text == "Authentication failed")
    }

    @Test func submit_transportFailure_staysCollapsed() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(
                result: .failure(ServerProbeError.transport("down"))
            ),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(viewModel.credentialsExpanded == false)
        #expect(viewModel.snackbar?.text == "Unable to reach the server")
    }

    @Test func fieldChange_clearsDetectedProtocolUntilReprobe() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        _ = await viewModel.submit()
        // After successful submit we dismiss in UI; here stay and dirty host
        viewModel.configure(
            initialProfile: ConnectionProfile(
                baseURL: "http://10.0.0.1:4096/",
                username: "opencode",
                password: "secret",
                serverProtocol: .v1,
                id: "p1",
                name: "P"
            ),
            mode: .edit
        )
        #expect(viewModel.canSubmitWithoutProbe == true)
        viewModel.baseURL = "http://10.0.0.9:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSubmitWithoutProbe == false)
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
        #expect(viewModel.primaryActionLabel == "Save")
        #expect(viewModel.canSubmitWithoutProbe == true)

        viewModel.baseURL = "http://10.0.0.9:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSubmitWithoutProbe == false)

        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSubmitWithoutProbe == true)

        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(store.saved.last?.serverProtocol == .v2)
    }

    @Test func hostIsBlank_detectsSchemeOnly() {
        #expect(ConnectionSetupViewModel.hostIsBlank(""))
        #expect(ConnectionSetupViewModel.hostIsBlank("http://"))
        #expect(ConnectionSetupViewModel.hostIsBlank("https://"))
        #expect(ConnectionSetupViewModel.hostIsBlank("http://10.0.0.1:4096/") == false)
    }

    @Test func applyPairingQR_fillsHostAndExpandsCredentials() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096"],"username":"opencode","password":"secret"}
            """
        )
        #expect(viewModel.baseURL == "http://192.168.0.160:4096/")
        #expect(viewModel.username == "opencode")
        #expect(viewModel.password == "secret")
        #expect(viewModel.credentialsExpanded == true)
        #expect(viewModel.snackbar == nil)
    }

    @Test func applyPairingQR_invalid_showsSnackbar() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.applyPairingQR("nope")
        #expect(viewModel.snackbar?.text == "This isn't an OpenCode 2 pairing QR")
        #expect(viewModel.baseURL.isEmpty)
    }

    @Test func pairingQRCameraDenied_showsSnackbar() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.pairingQRCameraDenied()
        #expect(viewModel.snackbar?.text == "Allow camera access to scan the pairing QR")
    }

    @Test func pairingQRCameraUnavailable_showsSnackbar() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.pairingQRCameraUnavailable()
        #expect(viewModel.snackbar?.text == "Camera isn't available on this device")
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
