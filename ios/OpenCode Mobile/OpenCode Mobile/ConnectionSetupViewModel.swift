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

    init(
        connectionProbeService: ConnectionProbing,
        connectionSessionCoordinator: ConnectionSessionCoordinator
    ) {
        self.connectionProbeService = connectionProbeService
        self.connectionSessionCoordinator = connectionSessionCoordinator
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
            state = .idle
        default:
            break
        }
    }

    func testConnection() async {
        state = .testing
        do {
            try await connectionProbeService.probe(connection: draftConnection())
            state = .testSucceeded
        } catch let error as ServerProbeError {
            state = .testFailed(error.probeUserMessage)
        } catch {
            state = .testFailed(error.localizedDescription)
        }
    }

    func saveConnection() async -> Bool {
        guard canSave else { return false }
        state = .saving
        await connectionSessionCoordinator.saveProfile(draftProfile())
        state = .testSucceeded
        return true
    }

    private func draftProfile() -> ConnectionProfile {
        ConnectionProfile(baseURL: baseURL, username: username, password: password)
    }

    private func draftConnection() -> ServerConnection {
        ServerConnection(profile: draftProfile())
    }
}
