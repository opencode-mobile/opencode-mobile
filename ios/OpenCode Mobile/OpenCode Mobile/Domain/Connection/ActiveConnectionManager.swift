import Foundation

final class ActiveConnectionManager: @unchecked Sendable {
    private let lock = NSLock()
    private let probeLock = NSLock()
    private let connectionProbing: (any ConnectionProbing)?
    private let profileStore: (any ConnectionProfileStore)?
    private var _connection: ServerConnection?
    private var _profile: ConnectionProfile?
    private var inFlightProbe: Task<Result<ServerProtocol, Error>, Never>?
    private var probeGeneration = 0
    private var probeFailed = false
    private var addressResolution: Task<Void, Never>?
    private var addressResolutionGeneration = 0

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

    var capabilities: ServerCapabilities {
        connection?.capabilities ?? .forProtocol(.v1)
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

    func reloadFromStore() async {
        guard let store = profileStore else { return }
        if let profile = await store.activeProfile() {
            apply(profile)
            await addressResolution?.value
        } else {
            disconnect()
        }
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
        let connection = ServerConnection(profile: stamped, serverProtocol: stamped.serverProtocol)
        lock.lock()
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
        let outcomes = await ServerAddressProbe.probe(
            urls: urls,
            username: profile.username,
            password: profile.password,
            probing: probing
        )
        let answered = outcomes.compactMap { url, result in
            ServerAddressProbe.hostAnswered(result) ? url : nil
        }
        let winner = PairingQRPayload.chooseAnsweredURL(candidates: urls, answered: answered)
        lock.lock()
        let current = addressResolutionGeneration == generation
        lock.unlock()
        guard current, let winner else { return }
        guard ConnectionProfileIdentity.normalizeBaseURL(winner)
            != ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL)
        else { return }
        var updated = profile
        updated.baseURL = winner
        install(updated, serverProtocol: updated.serverProtocol)
        try? await store.saveProfile(updated)
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
            let detected = try await probing.probe(connection: current)
            return .success(cacheDetectedProtocol(detected, for: current))
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
        for probed: ServerConnection
    ) -> ServerProtocol {
        lock.lock()
        defer { lock.unlock() }
        guard let active = _connection, active.hasSameIdentity(as: probed) else {
            return _connection?.serverProtocol ?? .v1
        }
        _connection = active.withProtocol(detected)
        persistLocked()
        return detected
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
    private let eventStream: @Sendable () -> (any ForegroundEventStream)?
    private let syncCoordinator: @Sendable () -> (any SyncCoordinator)?
    private let projectionCaches: @Sendable () -> (any ProjectionCaching)?
    private let bindReadyProfile: @Sendable (String?) async -> Void
    private let occupancy: @Sendable () -> StreamOccupancy
    private let emitProfileSwitched: @Sendable () -> Void
    private let hasActiveConnection: @Sendable () -> Bool
    private let profileChangeGate = ProfileChangeGate()
    private let lock = NSLock()
    private var profileSavedHandlers: [() -> Void] = []
    private var profileClearedHandlers: [() -> Void] = []
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
        await profileChangeGate.run {
            await self.connectionProfileStore.applyProfile(id: id)
            await self.applyActiveProfileChange(notify: .saved)
        }
    }

    func removeProfile(id: String) async {
        await profileChangeGate.run {
            await self.connectionProfileStore.removeProfile(id: id)
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

    private func applyActiveProfileChange(notify: ProfileNotify) async {
        lock.lock()
        switchGeneration += 1
        let generation = switchGeneration
        lock.unlock()

        if let stream = eventStream() {
            await stream.stop()
        }
        syncCoordinator()?.beginProfileSwitch(generation: generation)

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
            await rebindActive()
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

    private func rebindActive() async {
        await activeConnectionManager.reloadFromStore()
        if activeConnectionManager.currentProfile() == nil,
           let active = await connectionProfileStore.activeProfile()
        {
            activeConnectionManager.apply(active)
        }
    }

    private func notifySaved() {
        lock.lock()
        let handlers = profileSavedHandlers
        lock.unlock()
        handlers.forEach { $0() }
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
}

struct ConnectionProbeService: ConnectionProbing, Sendable {
    static let probeSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }()

    private let adapter = ProjectDomainAdapter()
    private let urlSession: URLSession

    init(urlSession: URLSession = ConnectionProbeService.probeSession) {
        self.urlSession = urlSession
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        let client = ProjectsAPIClient(fixedConnection: connection, urlSession: urlSession)
        do {
            let detected = try await client.detectProtocol()
            DiagnosticLog.info(.connection, "protocol=\(detected.wireValue)")
            return detected
        } catch {
            throw adapter.mapProbeError(error)
        }
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
