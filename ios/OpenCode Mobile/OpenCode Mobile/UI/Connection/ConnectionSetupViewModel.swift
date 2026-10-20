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

enum ConnectionSetupEntry: Equatable {
    case instructions
    case manual
}

enum ConnectionAddressRecovery: Equatable {
    case unreachable
    case unauthorized

    var message: String {
        switch self {
        case .unreachable:
            "Can't reach the saved address. Pair again."
        case .unauthorized:
            "This server rejected the saved sign-in. Pair again. A new password logs every device out."
        }
    }
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
    static let qrScanFailureMessage = "QR didn't connect. OpenCode 2's pairing API is still changing, so a scan can fail while this app is working. Enter the host and connect. Password is optional. A pairing code works too."

    static func savedAddressesHint(for urls: [String]) -> String {
        let kinds = urls.map { savedAddressKind(for: $0) }
        if !kinds.isEmpty, kinds.allSatisfy({ $0 == .tunnel }) {
            return "Remote tunnel saved. Nothing else to pick. It stays until you turn remote access off."
        }
        if kinds.contains(.tailscale), kinds.contains(.local), !kinds.contains(.tunnel) {
            return "Tailscale is used when it answers. Local network is the fallback."
        }
        return "Saved addresses are tried until one answers. Tailscale is preferred when it answers."
    }

    static func savedAddressTitle(for url: String) -> String {
        switch savedAddressKind(for: url) {
        case .tailscale:
            "Tailscale"
        case .tunnel:
            "Remote tunnel"
        case .local:
            "Local network"
        }
    }

    private enum SavedAddressKind {
        case tailscale
        case tunnel
        case local
    }

    private static func savedAddressKind(for url: String) -> SavedAddressKind {
        if PairingQRPayload.isTailscaleURL(url) { return .tailscale }
        if PairingQRPayload.isOpenTunnelURL(url) { return .tunnel }
        return .local
    }

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
    private(set) var entry: ConnectionSetupEntry = .instructions
    private(set) var addressRecovery: ConnectionAddressRecovery?

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
    /// Untrusted `http` hosts waiting for the user's "Connect anyway". `nil` when no warning shows.
    private(set) var cleartextWarningHosts: [String]?
    private var acknowledgedCleartextHosts: Set<String> = []
    private var pendingCleartextResume: (() async -> Bool)?
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

    var showsInstructionEssay: Bool { entry == .instructions && !usesQRLayout }

    var showsManualForm: Bool { entry == .manual && !usesQRLayout }

    var savedAddressRows: [ConnectionSetupAddressRow] {
        let chosen = ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        return serverURLs.map { url in
            let normalized = ConnectionProfileIdentity.normalizeBaseURL(url)
            let unreachable = addressProbeFinished && unreachableAddresses.contains(normalized)
            let isCurrent = addressProbeFinished && normalized == chosen && !unreachable
            let label = ConnectionProfileIdentity.hostLabel(normalized)
            return ConnectionSetupAddressRow(
                url: normalized,
                title: Self.savedAddressTitle(for: normalized),
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
        addressRecovery = nil
        addressProbeFinished = false
        unreachableAddresses = []
        entry = isEditingExistingProfile ? .manual : .instructions
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
        if addressRecovery != nil {
            switch state {
            case .connecting:
                return mode == .edit ? "Saving…" : "Connecting…"
            case .saving:
                return "Saving…"
            case .idle:
                return "Scan a new QR"
            }
        }
        if showsInstructionEssay {
            return "Scan QR"
        }
        switch state {
        case .connecting:
            return mode == .edit ? "Saving…" : "Connecting…"
        case .saving:
            return "Saving…"
        case .idle:
            return mode == .edit || qrPairingReady ? "Save" : "Connect"
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

    func showPairingCodeForm() {
        entry = .manual
        selectCredentialSegment(.pairingCode)
    }

    func showHostPasswordForm() {
        entry = .manual
        selectCredentialSegment(.password)
    }

    func showScanInstructions() {
        guard !usesQRLayout else { return }
        entry = .instructions
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
        let adoptedLink = adoptPastedConnectLink()
        return await connectAndSave(adoptedLink: adoptedLink)
    }

    /// Everything after the pasted-link adoption, so the cleartext warning can resume here.
    private func connectAndSave(adoptedLink: Bool) async -> Bool {
        let resume: () async -> Bool = { [weak self] in
            await self?.connectAndSave(adoptedLink: adoptedLink) ?? false
        }
        if adoptedLink {
            if let code = pendingPairingCode, !code.isEmpty, password.isEmpty {
                if requiresCleartextConfirmation(resume: resume) { return false }
                return await redeemThenSave(code: code)
            }
            if canSubmitWithoutProbe {
                return await persist()
            }
            if requiresCleartextConfirmation(resume: resume) { return false }
            return await probeThenSave()
        }
        switch credentialSegment {
        case .password:
            if canSubmitWithoutProbe {
                return await persist()
            }
            if requiresCleartextConfirmation(resume: resume) { return false }
            return await probeThenSave()
        case .pairingCode:
            let code = pairingCode.trimmingCharacters(in: .whitespacesAndNewlines)
            if code.isEmpty {
                snackbar = OpenCodeSnackbarMessage(text: "Enter a pairing code", variant: .error)
                return false
            }
            // Typed codes redeem for user opencode. The username field is hidden here.
            username = ConnectionProfile.formDefaults.username
            if requiresCleartextConfirmation(resume: resume) { return false }
            return await redeemThenSave(code: code)
        }
    }

    /// Shows the "not encrypted" warning when any address about to be contacted is plain `http`
    /// outside loopback, private, or Tailscale ranges. Nothing (password, pairing code, probe)
    /// is sent until the user confirms; `resume` continues where the caller stopped.
    private func requiresCleartextConfirmation(resume: @escaping () async -> Bool) -> Bool {
        let hosts = CleartextPolicy.untrustedCleartextHosts(in: urlsToProbe())
            .filter { !acknowledgedCleartextHosts.contains($0) }
        guard !hosts.isEmpty else { return false }
        cleartextWarningHosts = hosts
        pendingCleartextResume = resume
        state = .idle
        snackbar = nil
        return true
    }

    var cleartextWarningMessage: String {
        CleartextPolicy.warningMessage(hosts: cleartextWarningHosts ?? [])
    }

    /// "Connect anyway": remembers the hosts for this screen and continues the interrupted action.
    func confirmCleartextWarning() async -> Bool {
        guard let hosts = cleartextWarningHosts, let resume = pendingCleartextResume else { return false }
        acknowledgedCleartextHosts.formUnion(hosts)
        cleartextWarningHosts = nil
        pendingCleartextResume = nil
        return await resume()
    }

    /// "Cancel": nothing was sent. A scan that was waiting goes back to the host form.
    func dismissCleartextWarning() {
        cleartextWarningHosts = nil
        pendingCleartextResume = nil
        if qrScanPending {
            qrScanPending = false
            leaveQRMode()
        }
    }

    private func probeThenSave(
        persistOnSuccess: Bool = true,
        origin: ConnectFailureOrigin = .manual
    ) async -> Bool {
        state = .connecting
        snackbar = nil
        // Unauthenticated sweep first; the password then goes to the one chosen host.
        let resolution = await resolveAddresses(urlsToProbe(), password: password)
        noteAddressProbe(resolution)
        if let winner = resolution.chosen, let detected = resolution.reachableProtocol {
            addressRecovery = nil
            detectedProtocol = detected
            if ConnectionProfileIdentity.normalizeBaseURL(winner) !=
                ConnectionProfileIdentity.normalizeBaseURL(baseURL)
            {
                setBaseURLFromSystem(winner)
            }
            logConnectionAction(
                ProductAnalytics.Action.connectionTest,
                outcome: ProductAnalytics.Outcome.success
            )
            if persistOnSuccess {
                return await persist()
            }
            markQrPaired(detected)
            return true
        }
        detectedProtocol = nil
        handleProbeFailure(
            resolution.failure ?? .transport("Unable to reach the server"),
            origin: origin,
            resolution: resolution
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
        // No password yet: the sweep is unauthenticated and the code is redeemed on one host only.
        let resolution = await resolveAddresses(urlsToProbe(), password: "")
        noteAddressProbe(resolution)
        guard let winner = resolution.chosen else {
            detectedProtocol = nil
            handleProbeFailure(
                .transport("Unable to reach the server"),
                origin: origin,
                resolution: resolution
            )
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
                    addressRecovery = nil
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
            addressRecovery = nil
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
        addressRecovery = nil
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

    private func resolveAddresses(
        _ urls: [String],
        password: String
    ) async -> ServerAddressProbe.Resolution {
        await ServerAddressProbe.resolve(
            urls: urls,
            username: username,
            password: password,
            currentBaseURL: baseURL,
            probing: connectionProbeService
        )
    }

    private func noteAddressProbe(_ resolution: ServerAddressProbe.Resolution) {
        unreachableAddresses = resolution.unreachable
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
        addressRecovery = nil
        pendingPairingCode = nil
        qrPairingReady = false
        qrScanPending = false
        pastedConnectLinkUnconfirmed = false
        pairedProtocol = nil
        password = ""
        credentialSegment = .password
        entry = .manual
    }

    /// Probe every saved address and redeem a pairing code. Does not store the profile.
    private func completeScannedPairing() async {
        let resume: () async -> Bool = { [weak self] in
            await self?.completeScannedPairing()
            return false
        }
        if requiresCleartextConfirmation(resume: resume) { return }
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
        entry = .manual
    }

    private func handleProbeFailure(
        _ error: ServerProbeError,
        origin: ConnectFailureOrigin = .manual,
        resolution: ServerAddressProbe.Resolution? = nil
    ) {
        if origin == .scannedQr {
            presentScannedQrFailure()
            return
        }
        let recovery: ConnectionAddressRecovery?
        if let resolution {
            recovery = savedAddressRecovery(from: resolution)
        } else {
            recovery = authRecovery(for: error)
        }
        discardUnconfirmedPastedLayout()
        if let recovery, !serverURLs.isEmpty {
            addressRecovery = recovery
            requestPasswordFocus = false
            state = .idle
            snackbar = nil
            return
        }
        addressRecovery = nil
        let authFailed = error.probeStatus == .authFailed
        state = .idle
        if authFailed, credentialSegment == .password, !usesQRLayout {
            requestPasswordFocus = true
        }
        snackbar = OpenCodeSnackbarMessage(text: error.probeUserMessage, variant: .error)
    }

    /// Pair again only for a saved address list. A pasted link is not saved yet.
    private func savedAddressRecovery(
        from resolution: ServerAddressProbe.Resolution
    ) -> ConnectionAddressRecovery? {
        guard !serverURLs.isEmpty, !pastedConnectLinkUnconfirmed, !resolution.answers.isEmpty else { return nil }
        if resolution.reachableProtocol != nil {
            return nil
        }
        if resolution.nothingAnswered {
            return .unreachable
        }
        return isUnauthorized(resolution.failure) ? .unauthorized : nil
    }

    private func authRecovery(for error: ServerProbeError) -> ConnectionAddressRecovery? {
        guard !serverURLs.isEmpty, !pastedConnectLinkUnconfirmed, isUnauthorized(error) else { return nil }
        return .unauthorized
    }

    private func isUnauthorized(_ error: ServerProbeError?) -> Bool {
        guard case let .http(statusCode, _)? = error else { return false }
        return statusCode == 401 || statusCode == 403
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
        addressRecovery = nil
        entry = .manual
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
