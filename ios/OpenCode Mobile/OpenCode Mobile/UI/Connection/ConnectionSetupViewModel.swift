import Foundation
import Observation

enum ConnectionSetupState: Equatable {
    case idle
    case testing
    case testSucceeded
    case testFailed(String)
    case saving
}

enum ConnectionSetupMode: Equatable {
    case first
    case add
    case edit
}

@MainActor
@Observable
final class ConnectionSetupViewModel {
    var name = ""
    var baseURL = ConnectionProfile.formDefaults.baseURL
    var username = ConnectionProfile.formDefaults.username
    var password = ConnectionProfile.formDefaults.password
    private(set) var state: ConnectionSetupState = .idle
    private(set) var mode: ConnectionSetupMode = .first
    private(set) var profileId = ""

    private let connectionProbeService: ConnectionProbing
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private var detectedProtocol: ServerProtocol?
    private var savedBaseURL = ""
    private var savedUsername = ""
    private var savedPassword = ""

    init(
        connectionProbeService: ConnectionProbing,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        initialProfile: ConnectionProfile? = nil,
        mode: ConnectionSetupMode = .first
    ) {
        self.connectionProbeService = connectionProbeService
        self.connectionSessionCoordinator = connectionSessionCoordinator
        configure(initialProfile: initialProfile, mode: mode)
    }

    func configure(initialProfile: ConnectionProfile?, mode: ConnectionSetupMode = .first) {
        self.mode = mode
        detectedProtocol = nil
        state = .idle
        if let initialProfile, mode == .edit {
            name = initialProfile.name
            baseURL = initialProfile.baseURL
            username = initialProfile.username
            password = initialProfile.password
            profileId = initialProfile.id
            detectedProtocol = initialProfile.serverProtocol
            savedBaseURL = initialProfile.baseURL
            savedUsername = initialProfile.username
            savedPassword = initialProfile.password
        } else {
            name = ""
            baseURL = ConnectionProfile.formDefaults.baseURL
            username = ConnectionProfile.formDefaults.username
            password = ConnectionProfile.formDefaults.password
            profileId = ""
            savedBaseURL = ""
            savedUsername = ""
            savedPassword = ""
        }
    }

    var screenTitle: String {
        switch mode {
        case .first: "Connect to server"
        case .add: "Add server"
        case .edit: "Edit server"
        }
    }

    var canRemove: Bool {
        mode == .edit && !profileId.isEmpty
    }

    var canSave: Bool {
        state == .testSucceeded || connectionUnchanged
    }

    private var connectionUnchanged: Bool {
        mode == .edit &&
            ConnectionProfileIdentity.normalizeBaseURL(baseURL) ==
            ConnectionProfileIdentity.normalizeBaseURL(savedBaseURL) &&
            ConnectionProfileIdentity.normalizeUsername(username) ==
            ConnectionProfileIdentity.normalizeUsername(savedUsername) &&
            password == savedPassword
    }

    var testResultMessage: String? {
        switch state {
        case .testSucceeded:
            return ProbeStatus.reachable.label
        case let .testFailed(message):
            return message
        default:
            return nil
        }
    }

    var isTesting: Bool {
        state == .testing
    }

    var isSaving: Bool {
        state == .saving
    }

    func onFieldChanged() {
        switch state {
        case .testSucceeded, .testFailed:
            detectedProtocol = nil
            state = .idle
        default:
            break
        }
    }

    func testConnection() async {
        state = .testing
        do {
            detectedProtocol = try await connectionProbeService.probe(connection: draftConnection())
            state = .testSucceeded
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.success
            )
        } catch let error as ServerProbeError {
            detectedProtocol = nil
            state = .testFailed(error.probeUserMessage)
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
        } catch {
            detectedProtocol = nil
            state = .testFailed(error.localizedDescription)
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
        }
    }

    func saveConnection() async -> Bool {
        guard canSave else {
            logConnectionAction(
                ProductAnalytics.Action.connectionSave,
                outcome: ProductAnalytics.Outcome.blocked
            )
            return false
        }
        state = .saving
        do {
            try await connectionSessionCoordinator.saveProfile(draftProfile(), serverProtocol: detectedProtocol)
            state = .testSucceeded
            logConnectionAction(
                ProductAnalytics.Action.connectionSave,
                outcome: ProductAnalytics.Outcome.success
            )
            return true
        } catch is DuplicateConnectionProfileException {
            state = .testFailed("A server with this host and username is already saved")
            return false
        } catch {
            state = .testFailed(error.localizedDescription)
            return false
        }
    }

    func removeConnection() async {
        guard !profileId.isEmpty else { return }
        await connectionSessionCoordinator.removeProfile(id: profileId)
    }

    private func logConnectionAction(_ actionId: String, outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.connectionSetup,
            actionId: actionId,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    private func draftProfile() -> ConnectionProfile {
        ConnectionProfile(
            baseURL: baseURL,
            username: username,
            password: password,
            serverProtocol: detectedProtocol,
            id: profileId,
            name: name
        )
    }

    private func draftConnection() -> ServerConnection {
        ServerConnection(profile: draftProfile())
    }
}
