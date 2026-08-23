import Foundation
import Observation

enum ConnectionSetupState: Equatable {
    case idle
    case connecting
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
    var baseURL = ""
    var username = ConnectionProfile.formDefaults.username
    var password = ConnectionProfile.formDefaults.password
    private(set) var state: ConnectionSetupState = .idle
    private(set) var mode: ConnectionSetupMode = .first
    private(set) var profileId = ""
    private(set) var credentialsExpanded = false
    private(set) var requestPasswordFocus = false
    private(set) var snackbar: OpenCodeSnackbarMessage?

    private let connectionProbeService: ConnectionProbing
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private var detectedProtocol: ServerProtocol?
    private var savedBaseURL = ""
    private var savedUsername = ""
    private var savedPassword = ""
    private var savedProtocol: ServerProtocol?
    private var isEditingExistingProfile = false
    private var pairingCandidateURLs: [String] = []

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
        isEditingExistingProfile = initialProfile != nil && mode == .edit
        pairingCandidateURLs = []
        detectedProtocol = nil
        state = .idle
        credentialsExpanded = false
        requestPasswordFocus = false
        snackbar = nil
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
            savedProtocol = initialProfile.serverProtocol
        } else {
            name = ""
            baseURL = ConnectionProfile.formDefaults.baseURL
            username = ConnectionProfile.formDefaults.username
            password = ConnectionProfile.formDefaults.password
            profileId = ""
            savedBaseURL = ""
            savedUsername = ""
            savedPassword = ""
            savedProtocol = nil
        }
    }

    var screenTitle: String {
        switch mode {
        case .first: "Connect to server"
        case .add: "Add server"
        case .edit: "Edit server"
        }
    }

    var primaryActionLabel: String {
        switch state {
        case .connecting:
            mode == .edit ? "Saving…" : "Connecting…"
        case .saving:
            "Saving…"
        case .idle:
            mode == .edit ? "Save" : "Connect"
        }
    }

    var canRemove: Bool {
        mode == .edit && !profileId.isEmpty
    }

    var connectionUnchanged: Bool {
        isEditingExistingProfile &&
            ConnectionProfileIdentity.normalizeBaseURL(baseURL) ==
            ConnectionProfileIdentity.normalizeBaseURL(savedBaseURL) &&
            ConnectionProfileIdentity.normalizeUsername(username) ==
            ConnectionProfileIdentity.normalizeUsername(savedUsername) &&
            password == savedPassword
    }

    var canSubmitWithoutProbe: Bool {
        connectionUnchanged
    }

    var isBusy: Bool {
        state == .connecting || state == .saving
    }

    var primaryEnabled: Bool {
        !isBusy
    }

    func onFieldChanged() {
        detectedProtocol = connectionUnchanged ? savedProtocol : nil
    }

    func setCredentialsExpanded(_ expanded: Bool) {
        credentialsExpanded = expanded
    }

    func consumePasswordFocusRequest() {
        requestPasswordFocus = false
    }

    func consumeSnackbar() {
        snackbar = nil
    }

    func applyPairingQR(_ raw: String) {
        switch PairingQRPayload.parse(raw) {
        case let .success(credentials):
            pairingCandidateURLs = credentials.urls
            baseURL = credentials.baseURL
            username = credentials.username
            password = credentials.password
            credentialsExpanded = true
            snackbar = nil
            onFieldChanged()
            logConnectionAction(
                ProductAnalytics.Action.connectionPairingQRScan,
                outcome: ProductAnalytics.Outcome.success
            )
        case .failure(.invalidPayload):
            snackbar = OpenCodeSnackbarMessage(
                text: "This isn't an OpenCode 2 pairing QR",
                variant: .error
            )
            logConnectionAction(
                ProductAnalytics.Action.connectionPairingQRScan,
                outcome: ProductAnalytics.Outcome.failure
            )
        case .failure(.noReachableURL):
            snackbar = OpenCodeSnackbarMessage(
                text: "That QR only has a localhost address",
                variant: .error
            )
            logConnectionAction(
                ProductAnalytics.Action.connectionPairingQRScan,
                outcome: ProductAnalytics.Outcome.failure
            )
        }
    }

    func pairingQRCameraDenied() {
        snackbar = OpenCodeSnackbarMessage(
            text: "Allow camera access to scan the pairing QR",
            variant: .error
        )
    }

    func pairingQRCameraUnavailable() {
        snackbar = OpenCodeSnackbarMessage(
            text: "Camera isn't available on this device",
            variant: .error
        )
    }

    /// Primary CTA: rename-only Save skips probe; otherwise probe then save.
    @discardableResult
    func submit() async -> Bool {
        guard !isBusy else { return false }
        if Self.hostIsBlank(baseURL) {
            snackbar = OpenCodeSnackbarMessage(text: "Enter a host", variant: .error)
            return false
        }
        if canSubmitWithoutProbe {
            return await persist()
        }
        return await probeThenSave()
    }

    private func probeThenSave() async -> Bool {
        state = .connecting
        snackbar = nil
        let fieldURL = baseURL
        let probing = connectionProbeService
        let connections = urlsToProbe().map { url in
            (url, draftConnection(baseURL: url))
        }
        let outcomes: [(String, Result<ServerProtocol, Error>)] = await withTaskGroup(
            of: (String, Result<ServerProtocol, Error>).self
        ) { group in
            for (url, connection) in connections {
                group.addTask {
                    do {
                        let detected = try await probing.probe(connection: connection)
                        return (url, .success(detected))
                    } catch {
                        return (url, .failure(error))
                    }
                }
            }
            var collected: [(String, Result<ServerProtocol, Error>)] = []
            for await outcome in group {
                collected.append(outcome)
            }
            return collected
        }
        let reachable = outcomes.compactMap { url, result -> (String, ServerProtocol)? in
            guard case let .success(detected) = result else { return nil }
            return (url, detected)
        }
        if let winnerURL = PairingQRPayload.pickReachableURL(
            fieldURL: fieldURL,
            reachable: reachable.map(\.0)
        ), let winner = reachable.first(where: { $0.0 == winnerURL }) {
            detectedProtocol = winner.1
            if ConnectionProfileIdentity.normalizeBaseURL(winner.0) !=
                ConnectionProfileIdentity.normalizeBaseURL(baseURL)
            {
                baseURL = winner.0
            }
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.success
            )
            return await persist()
        }
        detectedProtocol = nil
        let probeErrors = outcomes.compactMap { _, result -> ServerProbeError? in
            guard case let .failure(error) = result else { return nil }
            return error as? ServerProbeError
        }
        handleProbeFailure(
            probeErrors.first(where: { $0.probeStatus == .authFailed })
                ?? probeErrors.last
                ?? .transport("Unable to reach the server")
        )
        logConnectionAction(
            ProductAnalytics.Action.connectionTest,
            outcome: ProductAnalytics.Outcome.failure
        )
        return false
    }

    private func urlsToProbe() -> [String] {
        let current = ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        guard !pairingCandidateURLs.isEmpty else { return [current] }
        let candidates = pairingCandidateURLs.map(ConnectionProfileIdentity.normalizeBaseURL)
        guard candidates.contains(current) else { return [current] }
        return [current] + candidates.filter { $0 != current }
    }

    private func handleProbeFailure(_ error: ServerProbeError) {
        let authFailed = error.probeStatus == .authFailed
        state = .idle
        if authFailed {
            credentialsExpanded = true
            requestPasswordFocus = true
        }
        snackbar = OpenCodeSnackbarMessage(text: error.probeUserMessage, variant: .error)
    }

    private func persist() async -> Bool {
        state = .saving
        snackbar = nil
        do {
            try await connectionSessionCoordinator.saveProfile(
                draftProfile(),
                serverProtocol: detectedProtocol
            )
            state = .idle
            logConnectionAction(
                ProductAnalytics.Action.connectionSave,
                outcome: ProductAnalytics.Outcome.success
            )
            return true
        } catch is DuplicateConnectionProfileException {
            state = .idle
            snackbar = OpenCodeSnackbarMessage(
                text: "A server with this host and username is already saved",
                variant: .error
            )
            logConnectionAction(
                ProductAnalytics.Action.connectionSave,
                outcome: ProductAnalytics.Outcome.failure
            )
            return false
        } catch {
            state = .idle
            snackbar = OpenCodeSnackbarMessage(
                text: error.localizedDescription,
                variant: .error
            )
            logConnectionAction(
                ProductAnalytics.Action.connectionSave,
                outcome: ProductAnalytics.Outcome.failure
            )
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
            serverProtocol: detectedProtocol ?? (connectionUnchanged ? savedProtocol : nil),
            id: profileId,
            name: name
        )
    }

    private func draftConnection(baseURL: String? = nil) -> ServerConnection {
        var profile = draftProfile()
        if let baseURL {
            profile.baseURL = baseURL
        }
        return ServerConnection(profile: profile)
    }

    static func hostIsBlank(_ baseURL: String) -> Bool {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }
        var rest = trimmed
        if rest.lowercased().hasPrefix("https://") {
            rest = String(rest.dropFirst("https://".count))
        } else if rest.lowercased().hasPrefix("http://") {
            rest = String(rest.dropFirst("http://".count))
        }
        return rest.trimmingCharacters(in: CharacterSet(charactersIn: "/")).isEmpty
    }
}
