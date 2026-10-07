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

enum ConnectionCredentialSegment: Equatable {
    case password
    case pairingCode
}

private enum ConnectFailureOrigin {
    case manual
    case scannedQr
}

struct ConnectionSetupAddressRow: Identifiable, Equatable {
    var url: String
    var title: String
    var subtitle: String
    var badge: String
    var dimmed: Bool
    var isCurrent: Bool

    var id: String { url }
}

@MainActor
@Observable
final class ConnectionSetupViewModel {
    static let qrSavedAddressesHint = "Both addresses are saved. Nothing to pick. The app uses Tailscale when it answers, and the local address when it does not. No password."
    static let qrScanFailureMessage = "QR didn't connect. OpenCode 2's pairing API is still changing, so a scan can fail while this app is working. Enter the host and connect. Password is optional. A pairing code works too."

    var name = ""
    var baseURL = "" {
        didSet {
            guard baseURL != oldValue, !suppressServerURLClear else { return }
            systemBaseURL = nil
            leaveQRMode()
        }
    }
    private(set) var serverURLs: [String] = []
    var username = ConnectionProfile.formDefaults.username
    var password = ConnectionProfile.formDefaults.password {
        didSet {
            if !password.isEmpty {
                pendingPairingCode = nil
            }
        }
    }
    var pairingCode = ""
    private(set) var credentialSegment: ConnectionCredentialSegment = .password
    private(set) var state: ConnectionSetupState = .idle
    private(set) var mode: ConnectionSetupMode = .first
    private(set) var profileId = ""
    private(set) var requestPasswordFocus = false
    private(set) var snackbar: OpenCodeSnackbarMessage?

    private let connectionProbeService: ConnectionProbing
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let pairingCodeRedeemer: any PairingCodeRedeeming
    private var detectedProtocol: ServerProtocol?
    private var savedBaseURL = ""
    private var savedUsername = ""
    private var savedPassword = ""
    private var savedProtocol: ServerProtocol?
    private var isEditingExistingProfile = false
    private var pendingPairingCode: String?
    private var scannedQrPassword = ""
    /// Set after a scanned QR has been probed and, when it carries a code, redeemed.
    /// Save then stores the profile without spending the code again.
    private(set) var qrPairingReady = false
    /// Camera scan still pairing. A saved address list is not a scan.
    private var qrScanPending = false
    /// Paste applied an address list that this session has not saved yet.
    private var pastedConnectLinkUnconfirmed = false
    private var pairedProtocol: ServerProtocol?
    private var addressProbeFinished = false
    private var unreachableAddresses = Set<String>()
    private var suppressServerURLClear = false
    /// Base URL written by QR apply, profile load, or address choice. The view
    /// change handler must not treat that write as the user editing the host.
    private var systemBaseURL: String?

    init(
        connectionProbeService: ConnectionProbing,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        pairingCodeRedeemer: any PairingCodeRedeeming = PairingCodeRedeemer(),
        initialProfile: ConnectionProfile? = nil,
        mode: ConnectionSetupMode = .first
    ) {
        self.connectionProbeService = connectionProbeService
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.pairingCodeRedeemer = pairingCodeRedeemer
        configure(initialProfile: initialProfile, mode: mode)
    }

    var usesQRLayout: Bool { !serverURLs.isEmpty }

    var savedAddressRows: [ConnectionSetupAddressRow] {
        let chosen = ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        return serverURLs.map { url in
            let normalized = ConnectionProfileIdentity.normalizeBaseURL(url)
            let unreachable = addressProbeFinished && unreachableAddresses.contains(normalized)
            let isCurrent = addressProbeFinished && normalized == chosen && !unreachable
            let label = ConnectionProfileIdentity.hostLabel(normalized)
            return ConnectionSetupAddressRow(
                url: normalized,
                title: PairingQRPayload.isTailscaleURL(normalized) ? "Tailscale" : "Local network",
                subtitle: unreachable ? "\(label) · not reachable" : label,
                badge: isCurrent ? "Using now" : "Saved",
                dimmed: unreachable,
                isCurrent: isCurrent
            )
        }
    }

    func configure(initialProfile: ConnectionProfile?, mode: ConnectionSetupMode = .first) {
        self.mode = mode
        isEditingExistingProfile = initialProfile != nil && mode == .edit
        pendingPairingCode = nil
        scannedQrPassword = ""
        pairingCode = ""
        credentialSegment = .password
        qrPairingReady = false
        qrScanPending = false
        pastedConnectLinkUnconfirmed = false
        pairedProtocol = nil
        detectedProtocol = nil
        state = .idle
        requestPasswordFocus = false
        snackbar = nil
        addressProbeFinished = false
        unreachableAddresses = []
        if let initialProfile, mode == .edit {
            name = initialProfile.name
            setBaseURLFromSystem(initialProfile.baseURL)
            serverURLs = ConnectionProfileIdentity.normalizedAddressList(initialProfile.serverURLs)
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
            setBaseURLFromSystem(ConnectionProfile.formDefaults.baseURL)
            serverURLs = []
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
            if mode == .edit || qrPairingReady { "Save" } else { "Connect" }
        }
    }

    /// A camera scan hides Save until pairing succeeds. Editing a saved QR profile, and a failed paste, keep the button.
    var showsPrimaryAction: Bool {
        if qrScanPending, !isBusy, !qrPairingReady { return false }
        return true
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
        guard pairedProtocol == nil else { return }
        detectedProtocol = connectionUnchanged ? savedProtocol : nil
    }

    /// View hook for host edits. System writes set `systemBaseURL` so they keep `serverURLs`.
    func onBaseURLChanged() {
        if systemBaseURL == baseURL {
            systemBaseURL = nil
            onFieldChanged()
            return
        }
        systemBaseURL = nil
        leaveQRMode()
        onFieldChanged()
    }

    func selectCredentialSegment(_ segment: ConnectionCredentialSegment) {
        credentialSegment = segment
    }

    func consumePasswordFocusRequest() {
        requestPasswordFocus = false
    }

    func consumeSnackbar() {
        snackbar = nil
    }

    func applyPairingQR(_ raw: String) async {
        switch PairingQRPayload.parse(raw) {
        case let .success(credentials):
            apply(credentials)
            pastedConnectLinkUnconfirmed = false
            qrScanPending = true
            logConnectionAction(
                ProductAnalytics.Action.connectionPairingQRScan,
                outcome: ProductAnalytics.Outcome.success
            )
            await completeScannedPairing()
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
        if qrScanPending, !qrPairingReady {
            return false
        }
        if qrPairingReady {
            return await persist()
        }
        if Self.hostIsBlank(baseURL) {
            snackbar = OpenCodeSnackbarMessage(text: "Enter a host", variant: .error)
            return false
        }
        if adoptPastedConnectLink() {
            if let code = pendingPairingCode, !code.isEmpty, password.isEmpty {
                return await redeemThenSave(code: code)
            }
            if canSubmitWithoutProbe {
                return await persist()
            }
            return await probeThenSave()
        }
        switch credentialSegment {
        case .password:
            if canSubmitWithoutProbe {
                return await persist()
            }
            return await probeThenSave()
        case .pairingCode:
            let code = pairingCode.trimmingCharacters(in: .whitespacesAndNewlines)
            if code.isEmpty {
                snackbar = OpenCodeSnackbarMessage(text: "Enter a pairing code", variant: .error)
                return false
            }
            return await redeemThenSave(code: code)
        }
    }

    private func probeThenSave(
        persistOnSuccess: Bool = true,
        origin: ConnectFailureOrigin = .manual
    ) async -> Bool {
        state = .connecting
        snackbar = nil
        let candidates = urlsToProbe()
        let outcomes = await probeOrigins(candidates)
        noteAddressProbe(outcomes)
        let reachable = outcomes.compactMap { url, result -> (String, ServerProtocol)? in
            guard case let .success(detected) = result else { return nil }
            return (url, detected)
        }
        if let winnerURL = PairingQRPayload.chooseAnsweredURL(
            candidates: candidates,
            answered: reachable.map(\.0)
        ), let winner = reachable.first(where: {
            ConnectionProfileIdentity.normalizeBaseURL($0.0) == winnerURL
        }) {
            detectedProtocol = winner.1
            if ConnectionProfileIdentity.normalizeBaseURL(winner.0) !=
                ConnectionProfileIdentity.normalizeBaseURL(baseURL)
            {
                setBaseURLFromSystem(winner.0)
            }
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.success
            )
            if persistOnSuccess {
                return await persist()
            }
            markQrPaired(winner.1)
            return true
        }
        detectedProtocol = nil
        let probeErrors = outcomes.compactMap { _, result -> ServerProbeError? in
            guard case let .failure(error) = result else { return nil }
            return error as? ServerProbeError
        }
        handleProbeFailure(
            probeErrors.first(where: { $0.probeStatus == .authFailed })
                ?? probeErrors.last
                ?? .transport("Unable to reach the server"),
            origin: origin
        )
        logConnectionAction(
            ProductAnalytics.Action.connectionTest,
            outcome: ProductAnalytics.Outcome.failure
        )
        return false
    }

    /// Unauthenticated reachability, then one redeem against the host that answered.
    private func redeemThenSave(
        code: String,
        persistOnSuccess: Bool = true,
        origin: ConnectFailureOrigin = .manual
    ) async -> Bool {
        state = .connecting
        snackbar = nil
        let candidates = urlsToProbe()
        let outcomes = await probeOrigins(candidates)
        noteAddressProbe(outcomes)
        let answered = outcomes.compactMap { url, result -> String? in
            ServerAddressProbe.hostAnswered(result) ? url : nil
        }
        guard let winner = PairingQRPayload.chooseAnsweredURL(candidates: candidates, answered: answered) else {
            detectedProtocol = nil
            let probeErrors = outcomes.compactMap { _, result -> ServerProbeError? in
                guard case let .failure(error) = result else { return nil }
                return error as? ServerProbeError
            }
            handleProbeFailure(probeErrors.last ?? .transport("Unable to reach the server"), origin: origin)
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
            return false
        }
        if ConnectionProfileIdentity.normalizeBaseURL(winner) !=
            ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        {
            setBaseURLFromSystem(winner)
        }
        let token: String
        do {
            token = try await pairingCodeRedeemer.redeem(baseURL: winner, code: code)
        } catch let error as PairingCodeRedeemError {
            detectedProtocol = nil
            switch error {
            case .expired:
                if origin == .scannedQr {
                    presentScannedQrFailure()
                } else {
                    discardUnconfirmedPastedLayout()
                    pendingPairingCode = nil
                    state = .idle
                    snackbar = OpenCodeSnackbarMessage(
                        text: "This pairing link expired or was already used",
                        variant: .error
                    )
                }
            case .unreachable:
                handleProbeFailure(.transport("Unable to reach the server"), origin: origin)
            }
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
            return false
        } catch {
            detectedProtocol = nil
            handleProbeFailure(.transport("Unable to reach the server"), origin: origin)
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
            return false
        }
        password = token
        do {
            let detected = try await connectionProbeService.probe(connection: draftConnection(baseURL: winner))
            detectedProtocol = detected
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.success
            )
            if persistOnSuccess {
                return await persist()
            }
            markQrPaired(detected)
            return true
        } catch let error as ServerProbeError {
            detectedProtocol = nil
            handleProbeFailure(error, origin: origin)
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
            return false
        } catch {
            detectedProtocol = nil
            handleProbeFailure(.transport("Unable to reach the server"), origin: origin)
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.failure
            )
            return false
        }
    }

    private func adoptPastedConnectLink() -> Bool {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("{") else { return false }
        guard case let .success(credentials) = PairingQRPayload.parse(trimmed),
              let code = credentials.code,
              !code.isEmpty
        else { return false }
        apply(credentials)
        pastedConnectLinkUnconfirmed = true
        return true
    }

    private func apply(_ credentials: PairingQRCredentials) {
        setBaseURLFromSystem(credentials.baseURL)
        serverURLs = ConnectionProfileIdentity.normalizedAddressList(credentials.urls)
        addressProbeFinished = false
        unreachableAddresses = []
        qrPairingReady = false
        pairedProtocol = nil
        scannedQrPassword = credentials.password
        username = credentials.username
        password = credentials.password
        if let code = credentials.code, !code.isEmpty {
            pendingPairingCode = code
        } else {
            pendingPairingCode = nil
        }
        snackbar = nil
        onFieldChanged()
    }

    private func probeOrigins(
        _ urls: [String]
    ) async -> [(String, Result<ServerProtocol, Error>)] {
        await ServerAddressProbe.probe(
            urls: urls,
            username: username,
            password: password,
            probing: connectionProbeService
        )
    }

    private func noteAddressProbe(_ outcomes: [(String, Result<ServerProtocol, Error>)]) {
        unreachableAddresses = Set(outcomes.compactMap { url, result in
            ServerAddressProbe.hostAnswered(result)
                ? nil
                : ConnectionProfileIdentity.normalizeBaseURL(url)
        })
        addressProbeFinished = true
    }

    private func urlsToProbe() -> [String] {
        let saved = ConnectionProfileIdentity.normalizedAddressList(serverURLs)
        let current = ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        if saved.isEmpty {
            return current.isEmpty ? [] : [current]
        }
        if current.isEmpty || saved.contains(current) {
            return saved
        }
        return ConnectionProfileIdentity.normalizedAddressList([current] + saved)
    }

    private func setBaseURLFromSystem(_ url: String) {
        systemBaseURL = url
        guard baseURL != url else { return }
        suppressServerURLClear = true
        baseURL = url
        suppressServerURLClear = false
    }

    private func leaveQRMode() {
        guard !serverURLs.isEmpty else { return }
        serverURLs = []
        unreachableAddresses = []
        addressProbeFinished = false
        pendingPairingCode = nil
        qrPairingReady = false
        qrScanPending = false
        pastedConnectLinkUnconfirmed = false
        pairedProtocol = nil
        password = ""
        credentialSegment = .password
    }

    /// Probe every saved address and redeem a pairing code. Does not store the profile.
    private func completeScannedPairing() async {
        if let code = pendingPairingCode, !code.isEmpty, password.isEmpty {
            _ = await redeemThenSave(code: code, persistOnSuccess: false, origin: .scannedQr)
            return
        }
        _ = await probeThenSave(persistOnSuccess: false, origin: .scannedQr)
    }

    private func markQrPaired(_ detected: ServerProtocol) {
        pairedProtocol = detected
        detectedProtocol = detected
        pendingPairingCode = nil
        qrPairingReady = true
        qrScanPending = false
        state = .idle
    }

    private func discardUnconfirmedPastedLayout() {
        guard pastedConnectLinkUnconfirmed else { return }
        pastedConnectLinkUnconfirmed = false
        pendingPairingCode = nil
        serverURLs = []
        unreachableAddresses = []
        addressProbeFinished = false
        qrPairingReady = false
        qrScanPending = false
        pairedProtocol = nil
    }

    private func handleProbeFailure(_ error: ServerProbeError, origin: ConnectFailureOrigin = .manual) {
        if origin == .scannedQr {
            presentScannedQrFailure()
            return
        }
        discardUnconfirmedPastedLayout()
        let authFailed = error.probeStatus == .authFailed
        state = .idle
        if authFailed, credentialSegment == .password, !usesQRLayout {
            requestPasswordFocus = true
        }
        snackbar = OpenCodeSnackbarMessage(text: error.probeUserMessage, variant: .error)
    }

    private func presentScannedQrFailure() {
        let keptPassword = scannedQrPassword
        pastedConnectLinkUnconfirmed = false
        serverURLs = []
        unreachableAddresses = []
        addressProbeFinished = false
        qrPairingReady = false
        qrScanPending = false
        pairedProtocol = nil
        credentialSegment = .password
        requestPasswordFocus = false
        if password != keptPassword {
            password = keptPassword
        }
        pendingPairingCode = nil
        state = .idle
        snackbar = OpenCodeSnackbarMessage(text: Self.qrScanFailureMessage, variant: .error)
    }

    private func persist() async -> Bool {
        pastedConnectLinkUnconfirmed = false
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
            serverURLs: serverURLs,
            username: ConnectionProfileIdentity.normalizeUsername(username),
            password: password,
            serverProtocol: pairedProtocol ?? detectedProtocol ?? (connectionUnchanged ? savedProtocol : nil),
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
