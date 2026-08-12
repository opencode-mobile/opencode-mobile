import Foundation
import Observation

enum ConnectionSetupState: Equatable {
    case idle
    case testing
    case testSucceeded
    case testFailed(String)
    case saving
}

@MainActor
@Observable
final class ConnectionSetupViewModel {
    var baseURL = ConnectionProfile.formDefaults.baseURL
    var username = ConnectionProfile.formDefaults.username
    var password = ConnectionProfile.formDefaults.password
    private(set) var state: ConnectionSetupState = .idle

    private let connectionProbeService: ConnectionProbing
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private var detectedProtocol: ServerProtocol?

    init(
        connectionProbeService: ConnectionProbing,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        initialProfile: ConnectionProfile? = nil
    ) {
        self.connectionProbeService = connectionProbeService
        self.connectionSessionCoordinator = connectionSessionCoordinator
        configure(initialProfile: initialProfile)
    }

    func configure(initialProfile: ConnectionProfile?) {
        if let initialProfile {
            baseURL = initialProfile.baseURL
            username = initialProfile.username
            password = initialProfile.password
        } else {
            baseURL = ConnectionProfile.formDefaults.baseURL
            username = ConnectionProfile.formDefaults.username
            password = ConnectionProfile.formDefaults.password
        }
        detectedProtocol = nil
        state = .idle
    }

    var canSave: Bool {
        state == .testSucceeded
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
        await connectionSessionCoordinator.saveProfile(draftProfile(), serverProtocol: detectedProtocol)
        state = .testSucceeded
        logConnectionAction(
            ProductAnalytics.Action.connectionSave,
            outcome: ProductAnalytics.Outcome.success
        )
        return true
    }

    /// Only an enum outcome is reported: never the base URL, username, password,
    /// or a server error message.
    private func logConnectionAction(_ actionId: String, outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.connectionSetup,
            actionId: actionId,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    private func draftProfile() -> ConnectionProfile {
        ConnectionProfile(baseURL: baseURL, username: username, password: password)
    }

    private func draftConnection() -> ServerConnection {
        ServerConnection(profile: draftProfile())
    }
}
