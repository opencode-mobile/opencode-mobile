import Foundation

final class ActiveConnectionManager: @unchecked Sendable {
    private let lock = NSLock()
    private let probeLock = NSLock()
    private let connectionProbing: (any ConnectionProbing)?
    private let profileStore: (any ConnectionProfileStore)?
    private var _connection: ServerConnection?
    private var _profile: ConnectionProfile?
    /// Health `version` from the last probe of the active connection (#721).
    private var _serverVersion: String?
    /// v2 session archive support from the server's API contract; false until a probe reads it.
    private var _v2Features = V2ServerFeatures()
    /// Server version `_v2Features` was read for, so a re-probe of the same release skips the fetch.
    private var _v2FeaturesVersion: String?
    private var inFlightProbe: Task<Result<ServerProtocol, Error>, Never>?
    private var probeGeneration = 0
    private var probeFailed = false
    private var addressResolution: Task<Void, Never>?
    private var addressResolutionGeneration = 0
    /// Fired after a background probe binds a different host than the one already in use.
    var onAddressRetargeted: ((Int) -> Void)?

    init(
        connectionProbing: (any ConnectionProbing)? = nil,
        profileStore: (any ConnectionProfileStore)? = nil
    ) {
        self.connectionProbing = connectionProbing
        self.profileStore = profileStore
    }

    var connection: ServerConnection? {
        lock.lock()
        defer { lock.unlock() }
        return _connection
    }

    var serverProtocol: ServerProtocol? {
        connection?.serverProtocol
    }

    /// Server version reported by health when this connection was last probed.
    var serverVersion: String? {
        lock.lock()
        defer { lock.unlock() }
        return _serverVersion
    }

    var capabilities: ServerCapabilities {
        lock.lock()
        defer { lock.unlock() }
        guard let connection = _connection else { return .forProtocol(.v1) }
        return .forProtocol(connection.serverProtocol ?? .v1, v2Features: _v2Features)
    }

    /// `paths.tmp` where uploads go, only while streamed upload is on for this connection.
    var streamedUploadDirectory: String? {
        lock.lock()
        defer { lock.unlock() }
        guard _connection?.serverProtocol == .v2, _v2Features.streamedUpload else { return nil }
        return _v2Features.uploadDirectory
    }

    /// A live upload showed the route no longer matches the contract: stop using it on this
    /// connection. Probing again for the same release keeps the cleared flag.
    func disableStreamedUpload() {
        lock.lock()
        _v2Features.streamedUploadRoute = false
        lock.unlock()
    }

    func ensureLoaded(from store: ConnectionProfileStore) async {
        lock.lock()
        let needsLoad = _connection == nil
        lock.unlock()
        guard needsLoad else { return }

        if let profile = await store.activeProfile() {
            install(profile)
            scheduleAddressResolution(for: profile, store: store)
            await addressResolution?.value
        }
    }

    /// Binds the stored address immediately. When [waitForAddressResolution] is false, a better
    /// saved address is applied later and [onAddressRetargeted] fires if the host changes.
    func reloadFromStore(waitForAddressResolution: Bool = true) async {
        guard let store = profileStore else { return }
        if let profile = await store.activeProfile() {
            apply(profile)
            if waitForAddressResolution {
                await addressResolution?.value
            }
        } else {
            disconnect()
        }
    }

    func addressResolutionIsCurrent(_ generation: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return addressResolutionGeneration == generation
    }

    func apply(_ profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) {
        install(profile, serverProtocol: serverProtocol)
        if let store = profileStore {
            scheduleAddressResolution(for: profile, store: store)
        }
    }

    private func install(_ profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) {
        var stamped = profile
        stamped.serverProtocol = serverProtocol ?? profile.serverProtocol
        guard let connection = ServerConnection(profile: stamped, serverProtocol: stamped.serverProtocol) else {
            // An unparseable address gets no connection at all. Falling back to a default
            // host would send this profile's password somewhere the user never chose.
            DiagnosticLog.warning(.connection, "profile baseURL does not parse; not connecting")
            lock.lock()
            _profile = stamped
            _connection = nil
            _serverVersion = nil
            probeFailed = false
            lock.unlock()
            invalidateInFlightProbe()
            return
        }
        lock.lock()
        // The version belongs to one server: switching profiles, or editing this profile to
        // point at another address or user, must not report the previous machine's version.
        let sameServer = _profile?.id == stamped.id
            && (_connection.map { $0.hasSameIdentity(as: connection) } ?? false)
        if !sameServer {
            _serverVersion = nil
            forgetV2FeaturesLocked()
        }
        _profile = stamped
        _connection = connection
        probeFailed = false
        lock.unlock()
        invalidateInFlightProbe()
    }

    /// Probes every saved address, then keeps the winner on the same profile id.
    private func scheduleAddressResolution(for profile: ConnectionProfile, store: ConnectionProfileStore) {
        lock.lock()
        addressResolutionGeneration += 1
        let generation = addressResolutionGeneration
        lock.unlock()
        let urls = ConnectionProfileIdentity.normalizedAddressList(profile.serverURLs)
        guard !urls.isEmpty, let probing = connectionProbing else {
            addressResolution = nil
            return
        }
        addressResolution = Task { [self] in
            await self.resolveSavedAddresses(
                profile,
                urls: urls,
                probing: probing,
                store: store,
                generation: generation
            )
        }
    }

    private func resolveSavedAddresses(
        _ profile: ConnectionProfile,
        urls: [String],
        probing: any ConnectionProbing,
        store: ConnectionProfileStore,
        generation: Int
    ) async {
        // Credentials go to one host only, and only a host that accepted them wins.
        // A 401 (or any other failure) from the chosen host leaves the stored address alone.
        let resolution = await ServerAddressProbe.resolve(
            urls: urls,
            username: profile.username,
            password: profile.password,
            currentBaseURL: profile.baseURL,
            probing: probing
        )
        lock.lock()
        let current = addressResolutionGeneration == generation
        lock.unlock()
        guard current, let winner = resolution.chosen, let detected = resolution.reachableProtocol else { return }
        guard ConnectionProfileIdentity.normalizeBaseURL(winner)
            != ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL)
        else { return }
        var updated = profile
        updated.baseURL = winner
        updated.serverProtocol = detected
        install(updated, serverProtocol: detected)
        try? await store.saveProfile(updated)
        lock.lock()
        let stillCurrent = addressResolutionGeneration == generation
        lock.unlock()
        guard stillCurrent else { return }
        onAddressRetargeted?(generation)
    }

    func applyConnection(_ connection: ServerConnection) {
        lock.lock()
        _connection = connection
        probeFailed = false
        lock.unlock()
        invalidateInFlightProbe()
    }

    func updateProtocol(_ serverProtocol: ServerProtocol) {
        lock.lock()
        defer { lock.unlock() }
        guard let current = _connection else { return }
        _connection = current.withProtocol(serverProtocol)
        persistLocked()
    }

    /// Last-known protocol for opening SSE without waiting on detect.
    func protocolHint() -> ServerProtocol {
        serverProtocol ?? .v1
    }

    func resetProbeFailure() {
        lock.lock()
        probeFailed = false
        lock.unlock()
    }

    /// Implicit protocol resolution for repository remotes.
    /// Cache hit returns immediately. Cache miss probes the active connection,
    /// caches the result, and returns it. Missing connection, missing probe, or
    /// a thrown probe returns `.v1` without throwing.
    func currentProtocolOrProbe() async -> ServerProtocol {
        (try? await sharedProbe(force: false).get()) ?? .v1
    }

    /// Home / Test Connection: join the in-flight detect and throw on failure.
    func probeActive() async throws -> ServerProtocol {
        try await sharedProbe(force: true).get()
    }

    /// Re-runs detection on the active connection, so a server replaced at the same address by
    /// the other protocol moves the profile to it. A rejected credential is not an answer.
    func redetectProtocol() async -> ProtocolRedetection {
        guard connection != nil, connectionProbing != nil else { return .skipped }
        switch await sharedProbe(force: true) {
        case let .success(detected):
            return .detected(detected)
        case let .failure(error):
            if error is CancellationError { return .skipped }
            if (error as? ServerProbeError)?.probeStatus == .authFailed { return .skipped }
            return .unreachable
        }
    }

    private func sharedProbe(force: Bool) async -> Result<ServerProtocol, Error> {
        if !force, let cached = serverProtocol {
            return .success(cached)
        }
        lock.lock()
        let failed = probeFailed
        lock.unlock()
        if !force, failed {
            return .success(.v1)
        }
        guard connection != nil, connectionProbing != nil else {
            return .success(.v1)
        }

        let task: Task<Result<ServerProtocol, Error>, Never>
        probeLock.lock()
        if !force, let cached = serverProtocol {
            probeLock.unlock()
            return .success(cached)
        }
        lock.lock()
        let failedNow = probeFailed
        lock.unlock()
        if !force, failedNow {
            probeLock.unlock()
            return .success(.v1)
        }
        if let existing = inFlightProbe {
            task = existing
        } else {
            if force {
                lock.lock()
                probeFailed = false
                lock.unlock()
            }
            let generation = probeGeneration
            let skipCache = force
            let created = Task { [self] in
                await self.performProtocolProbe(generation: generation, skipCache: skipCache)
            }
            inFlightProbe = created
            task = created
        }
        probeLock.unlock()
        return await task.value
    }

    private func performProtocolProbe(generation: Int, skipCache: Bool) async -> Result<ServerProtocol, Error> {
        defer {
            probeLock.lock()
            if probeGeneration == generation {
                inFlightProbe = nil
            }
            probeLock.unlock()
        }
        if !skipCache, let cached = serverProtocol {
            return .success(cached)
        }
        guard let current = connection, let probing = connectionProbing else {
            return .success(.v1)
        }
        do {
            let outcome = try await probing.probeServer(connection: current)
            let features = outcome.serverProtocol == .v2
                ? await probeV2Features(probing: probing, connection: current, version: outcome.serverVersion)
                : nil
            return .success(
                cacheDetectedProtocol(
                    outcome.serverProtocol,
                    serverVersion: outcome.serverVersion,
                    v2Features: features,
                    for: current
                )
            )
        } catch is CancellationError {
            return .failure(CancellationError())
        } catch {
            lock.lock()
            probeFailed = true
            lock.unlock()
            return .failure(error)
        }
    }

    /// Caches `detected` only when the active connection is still the probed one.
    /// A disconnect or profile switch during the await must not stamp the old
    /// server's protocol onto a different connection.
    private func cacheDetectedProtocol(
        _ detected: ServerProtocol,
        serverVersion: String?,
        v2Features: V2ServerFeatures?,
        for probed: ServerConnection
    ) -> ServerProtocol {
        lock.lock()
        defer { lock.unlock() }
        guard let active = _connection, active.hasSameIdentity(as: probed) else {
            return _connection?.serverProtocol ?? .v1
        }
        _connection = active.withProtocol(detected)
        _serverVersion = serverVersion
        _v2Features = v2Features ?? V2ServerFeatures()
        _v2FeaturesVersion = v2Features == nil || v2Features?.uploadDirectoryUnread == true ? nil : serverVersion
        persistLocked()
        return detected
    }

    /// Reads v2 features once per server release. A failed read returns nil and is not
    /// remembered, so the next probe tries again; until then archive and Last turn stay hidden.
    /// Neither is a read whose `/api/info` failed, so streamed upload can turn on later.
    private func probeV2Features(
        probing: any ConnectionProbing,
        connection: ServerConnection,
        version: String?
    ) async -> V2ServerFeatures? {
        lock.lock()
        let cached = version != nil && _v2FeaturesVersion == version ? _v2Features : nil
        lock.unlock()
        if let cached { return cached }
        do {
            return try await probing.probeV2Features(connection: connection)
        } catch {
            DiagnosticLog.warning(.connection, "probe /\(V2ServerContract.openAPIPath) failed")
            return nil
        }
    }

    private func forgetV2FeaturesLocked() {
        _v2Features = V2ServerFeatures()
        _v2FeaturesVersion = nil
    }

    private func persistLocked() {
        guard let store = profileStore, let profile = currentProfileLocked() else { return }
        Task {
            try? await store.saveProfile(profile)
        }
    }

    private func currentProfileLocked() -> ConnectionProfile? {
        guard var profile = _profile else { return nil }
        if let connection = _connection {
            profile.password = connection.password ?? ""
            profile.serverProtocol = connection.serverProtocol ?? profile.serverProtocol
        }
        return profile
    }

    private func invalidateInFlightProbe() {
        probeLock.lock()
        probeGeneration += 1
        inFlightProbe = nil
        probeLock.unlock()
    }

    func disconnect() {
        lock.lock()
        addressResolutionGeneration += 1
        _connection = nil
        _profile = nil
        _serverVersion = nil
        forgetV2FeaturesLocked()
        probeFailed = false
        lock.unlock()
        addressResolution = nil
        invalidateInFlightProbe()
    }

    func currentProfile() -> ConnectionProfile? {
        lock.lock()
        defer { lock.unlock() }
        return currentProfileLocked()
    }
}

final class ConnectionSessionCoordinator: @unchecked Sendable {
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionProfileStore: ConnectionProfileStore
    private let secureCredentialStore: SecureCredentialStore
    private let sessionModelSelectionStore: SessionModelSelectionStore
    private let composerDraftStore: ComposerDraftStore
    private let sessionCache: @Sendable () -> (any SessionCache)?
    private let feedbackRefreshTokenStore: (any FeedbackRefreshTokenStore)?
    private let eventStream: @Sendable () -> (any ForegroundEventStream)?
    private let syncCoordinator: @Sendable () -> (any SyncCoordinator)?
    private let projectionCaches: @Sendable () -> (any ProjectionCaching)?
    private let bindReadyProfile: @Sendable (String?) async -> Void
    private let occupancy: @Sendable () -> StreamOccupancy
    private let emitProfileSwitched: @Sendable () -> Void
    private let hasActiveConnection: @Sendable () -> Bool
    private let profileChangeGate = ProfileChangeGate()
    private let lock = NSLock()
    private var profileSwitchingHandlers: [(String) -> Void] = []
    private var profileSavedHandlers: [() -> Void] = []
    private var profileClearedHandlers: [() -> Void] = []
    private var connectionRetargetedHandlers: [() -> Void] = []
    private var switchGeneration: UInt64 = 0

    var profileGeneration: UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return switchGeneration
    }

    init(
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: ConnectionProfileStore,
        secureCredentialStore: SecureCredentialStore = KeychainCredentialStore(),
        sessionModelSelectionStore: SessionModelSelectionStore = UserDefaultsSessionModelSelectionStore(),
        composerDraftStore: ComposerDraftStore = UserDefaultsComposerDraftStore(),
        sessionCache: @escaping @Sendable () -> (any SessionCache)? = { nil },
        feedbackRefreshTokenStore: (any FeedbackRefreshTokenStore)? = nil,
        eventStream: @escaping @Sendable () -> (any ForegroundEventStream)? = { nil },
        syncCoordinator: @escaping @Sendable () -> (any SyncCoordinator)? = { nil },
        projectionCaches: @escaping @Sendable () -> (any ProjectionCaching)? = { nil },
        bindReadyProfile: @escaping @Sendable (String?) async -> Void = { _ in },
        occupancy: @escaping @Sendable () -> StreamOccupancy = { .active },
        emitProfileSwitched: @escaping @Sendable () -> Void = {},
        hasActiveConnection: @escaping @Sendable () -> Bool = { false }
    ) {
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
        self.sessionCache = sessionCache
        self.feedbackRefreshTokenStore = feedbackRefreshTokenStore
        self.secureCredentialStore = secureCredentialStore
        self.sessionModelSelectionStore = sessionModelSelectionStore
        self.composerDraftStore = composerDraftStore
        self.eventStream = eventStream
        self.syncCoordinator = syncCoordinator
        self.projectionCaches = projectionCaches
        self.bindReadyProfile = bindReadyProfile
        self.occupancy = occupancy
        self.emitProfileSwitched = emitProfileSwitched
        self.hasActiveConnection = hasActiveConnection
        activeConnectionManager.onAddressRetargeted = { [weak self] generation in
            guard let self else { return }
            Task { await self.restartAfterAddressRetarget(generation: generation) }
        }
    }

    func onProfileSwitching(_ handler: @escaping (String) -> Void) {
        lock.lock()
        profileSwitchingHandlers.append(handler)
        lock.unlock()
    }

    func onProfileSaved(_ handler: @escaping () -> Void) {
        lock.lock()
        profileSavedHandlers.append(handler)
        lock.unlock()
    }

    func onProfileCleared(_ handler: @escaping () -> Void) {
        lock.lock()
        profileClearedHandlers.append(handler)
        lock.unlock()
    }

    func onConnectionRetargeted(_ handler: @escaping () -> Void) {
        lock.lock()
        connectionRetargetedHandlers.append(handler)
        lock.unlock()
    }

    func listProfiles() async -> [ConnectionProfile] {
        await connectionProfileStore.listProfiles()
    }

    func saveProfile(_ profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) async throws {
        try await profileChangeGate.run {
            var stamped = profile
            stamped.serverProtocol = serverProtocol ?? profile.serverProtocol
            let wasNew = stamped.id.isEmpty
            try await self.connectionProfileStore.saveProfile(stamped)
            let active = await self.connectionProfileStore.activeProfile()
            let savedIsActive = wasNew || (!stamped.id.isEmpty && active?.id == stamped.id)
            if savedIsActive {
                await self.applyActiveProfileChange(notify: .saved)
            }
        }
    }

    func switchProfile(id: String) async {
        notifySwitching(id)
        await profileChangeGate.run {
            await self.connectionProfileStore.applyProfile(id: id)
            await self.applyActiveProfileChange(notify: .saved, waitForAddressResolution: false)
        }
    }

    /// Forgets the profile and everything stored under it: password, transcripts and
    /// activity in the session cache, composer drafts, and model selections. Re-adding the
    /// same server later starts clean instead of resurrecting the old state.
    func removeProfile(id: String) async {
        await profileChangeGate.run {
            let removed = await self.connectionProfileStore.listProfiles().first { $0.id == id }
            await self.connectionProfileStore.removeProfile(id: id)
            if let removed {
                let identity = ModelSelectionConnectionIdentity(
                    serverBaseURL: removed.baseURL,
                    username: removed.username
                )
                await self.sessionCache()?.purgeConnection(profileID: removed.id, serverBaseURL: removed.baseURL)
                await self.sessionModelSelectionStore.clearConnection(connectionIdentity: identity)
                self.composerDraftStore.clearConnection(connectionIdentity: identity)
            }
            if await self.connectionProfileStore.activeProfile() == nil {
                await self.applyActiveProfileChange(notify: .cleared)
            } else {
                await self.applyActiveProfileChange(notify: .saved)
            }
        }
    }

    func clearConnectionData() async {
        await profileChangeGate.run {
            self.secureCredentialStore.clearAll()
            await self.connectionProfileStore.clearAll()
            await self.sessionModelSelectionStore.clearAll()
            self.composerDraftStore.clearAll()
            self.feedbackRefreshTokenStore?.delete()
            await self.applyActiveProfileChange(notify: .cleared)
        }
    }

    func currentProfile() -> ConnectionProfile? {
        activeConnectionManager.currentProfile()
    }

    private enum ProfileNotify {
        case saved
        case cleared
    }

    private func applyActiveProfileChange(
        notify: ProfileNotify,
        waitForAddressResolution: Bool = true
    ) async {
        lock.lock()
        switchGeneration += 1
        let generation = switchGeneration
        lock.unlock()

        // Cancel in-flight loads before the disconnect, so stopping the stream does not
        // refetch the server we are leaving.
        syncCoordinator()?.beginProfileSwitch(generation: generation)
        if let stream = eventStream() {
            await stream.stop()
        }

        let nextProfile = await connectionProfileStore.activeProfile()
        await MainActor.run {
            if notify == .cleared {
                self.projectionCaches()?.clearAll()
            } else {
                self.projectionCaches()?.clearLiveProjections()
            }
        }
        await bindReadyProfile(nextProfile?.id)

        if nextProfile == nil {
            activeConnectionManager.disconnect()
        } else {
            await rebindActive(waitForAddressResolution: waitForAddressResolution)
        }

        emitProfileSwitched()

        if occupancy() != .stopped, hasActiveConnection(), let stream = eventStream() {
            stream.start()
        }

        switch notify {
        case .saved:
            notifySaved()
        case .cleared:
            notifyCleared()
        }
    }

    private func rebindActive(waitForAddressResolution: Bool) async {
        await activeConnectionManager.reloadFromStore(waitForAddressResolution: waitForAddressResolution)
        if activeConnectionManager.currentProfile() == nil,
           let active = await connectionProfileStore.activeProfile()
        {
            activeConnectionManager.apply(active)
        }
    }

    private func notifySwitching(_ id: String) {
        lock.lock()
        let handlers = profileSwitchingHandlers
        lock.unlock()
        handlers.forEach { $0(id) }
    }

    private func notifySaved() {
        lock.lock()
        let handlers = profileSavedHandlers
        lock.unlock()
        handlers.forEach { $0() }
    }

    private func notifyRetargeted() {
        lock.lock()
        let handlers = connectionRetargetedHandlers
        lock.unlock()
        handlers.forEach { $0() }
    }

    private func restartAfterAddressRetarget(generation: Int) async {
        guard activeConnectionManager.addressResolutionIsCurrent(generation) else { return }
        await profileChangeGate.run {
            guard self.activeConnectionManager.addressResolutionIsCurrent(generation) else { return }
            guard self.hasActiveConnection() else { return }
            if let stream = self.eventStream() {
                await stream.stop()
                if self.occupancy() != .stopped {
                    stream.start()
                }
            }
            self.notifyRetargeted()
        }
    }

    private func notifyCleared() {
        lock.lock()
        let handlers = profileClearedHandlers
        lock.unlock()
        handlers.forEach { $0() }
    }
}

private actor ProfileChangeGate {
    func run<T: Sendable>(_ operation: @Sendable () async throws -> T) async rethrows -> T {
        try await operation()
    }
}

enum ProbeStatus: Equatable {
    case reachable
    case authFailed
    case unreachable
}

protocol ConnectionProbing: Sendable {
    func probe(connection: ServerConnection) async throws -> ServerProtocol
    /// Protocol plus the server's health `version`. Defaults to `probe` with no version.
    func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome
    /// Release-dependent v2 features (opencode 2.1.0+). Throws when the server's API contract
    /// could not be read, so the caller can retry later. Defaults to none.
    func probeV2Features(connection: ServerConnection) async throws -> V2ServerFeatures
}

extension ConnectionProbing {
    func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome {
        ServerProbeOutcome(serverProtocol: try await probe(connection: connection), serverVersion: nil)
    }

    func probeV2Features(connection _: ServerConnection) async throws -> V2ServerFeatures {
        V2ServerFeatures()
    }
}

/// Result of a health probe: the detected protocol and the health `version`.
nonisolated struct ServerProbeOutcome: Equatable, Sendable {
    let serverProtocol: ServerProtocol
    let serverVersion: String?
}

struct ConnectionProbeService: ConnectionProbing, Sendable {
    static let probeSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: RedirectPolicyDelegate.shared, delegateQueue: nil)
    }()

    private let adapter = ProjectDomainAdapter()
    private let urlSession: URLSession

    init(urlSession: URLSession = ConnectionProbeService.probeSession) {
        self.urlSession = urlSession
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        try await probeServer(connection: connection).serverProtocol
    }

    func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome {
        let client = ProjectsAPIClient(fixedConnection: connection, urlSession: urlSession)
        do {
            let outcome = try await client.detectServer()
            DiagnosticLog.info(.connection, "protocol=\(outcome.serverProtocol.wireValue)")
            return outcome
        } catch {
            throw adapter.mapProbeError(error)
        }
    }

    func probeV2Features(connection: ServerConnection) async throws -> V2ServerFeatures {
        let client = ProjectsAPIClient(fixedConnection: connection, urlSession: urlSession)
        var features = try V2ServerContract.features(data: try await client.getV2OpenAPIDocument())
        if features.streamedUploadRoute {
            do {
                features.uploadDirectory = try await client.getV2TmpDirectory()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                features.uploadDirectoryUnread = true
            }
        }
        return features
    }
}

extension ServerProbeError {
    var probeStatus: ProbeStatus {
        switch self {
        case let .http(statusCode, _):
            return (statusCode == 401 || statusCode == 403) ? .authFailed : .unreachable
        case .transport, .unhealthy:
            return .unreachable
        }
    }

    var probeUserMessage: String {
        switch self {
        case .transport:
            return "Unable to reach the server"
        case let .http(statusCode, _):
            if statusCode == 401 || statusCode == 403 {
                return "Authentication failed"
            }
            return "Unable to reach the server"
        case .unhealthy:
            return "Server reported unhealthy"
        }
    }
}

extension ProbeStatus {
    var label: String {
        switch self {
        case .reachable:
            return "Server reachable"
        case .authFailed:
            return "Authentication failed"
        case .unreachable:
            return "Unable to reach the server"
        }
    }
}
