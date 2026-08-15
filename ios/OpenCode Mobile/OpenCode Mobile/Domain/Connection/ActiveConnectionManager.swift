import Foundation

final class ActiveConnectionManager: @unchecked Sendable {
    private let lock = NSLock()
    private let probeLock = NSLock()
    private let connectionProbing: (any ConnectionProbing)?
    private let profileStore: (any ConnectionProfileStore)?
    private var _connection: ServerConnection?
    private var inFlightProbe: Task<Result<ServerProtocol, Error>, Never>?
    private var probeGeneration = 0
    private var probeFailed = false

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
            apply(profile)
        }
    }

    func apply(_ profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) {
        let connection = ServerConnection(profile: profile, serverProtocol: serverProtocol)
        lock.lock()
        _connection = connection
        probeFailed = false
        lock.unlock()
        invalidateInFlightProbe()
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
            await store.saveProfile(profile)
        }
    }

    private func currentProfileLocked() -> ConnectionProfile? {
        guard let connection = _connection else { return nil }
        return ConnectionProfile(
            baseURL: connection.baseURL.absoluteString,
            username: connection.username,
            password: connection.password ?? "",
            serverProtocol: connection.serverProtocol
        )
    }

    private func invalidateInFlightProbe() {
        probeLock.lock()
        probeGeneration += 1
        inFlightProbe = nil
        probeLock.unlock()
    }

    func disconnect() {
        lock.lock()
        _connection = nil
        probeFailed = false
        lock.unlock()
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
    private let lock = NSLock()
    private var profileSavedHandlers: [() -> Void] = []
    private var profileClearedHandlers: [() -> Void] = []

    init(
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: ConnectionProfileStore,
        secureCredentialStore: SecureCredentialStore = KeychainCredentialStore(),
        sessionModelSelectionStore: SessionModelSelectionStore = UserDefaultsSessionModelSelectionStore()
    ) {
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
        self.secureCredentialStore = secureCredentialStore
        self.sessionModelSelectionStore = sessionModelSelectionStore
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

    func saveProfile(_ profile: ConnectionProfile, serverProtocol: ServerProtocol? = nil) async {
        var stamped = profile
        stamped.serverProtocol = serverProtocol ?? profile.serverProtocol
        await connectionProfileStore.saveProfile(stamped)
        activeConnectionManager.apply(stamped, serverProtocol: stamped.serverProtocol)
        lock.lock()
        let handlers = profileSavedHandlers
        lock.unlock()
        handlers.forEach { $0() }
    }

    func clearConnectionData() async {
        secureCredentialStore.clearAll()
        await connectionProfileStore.clearAll()
        await sessionModelSelectionStore.clearAll()
        activeConnectionManager.disconnect()
        let handlers = profileClearedHandlers
        handlers.forEach { $0() }
    }

    func currentProfile() -> ConnectionProfile? {
        activeConnectionManager.currentProfile()
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
        case let .http(_, message):
            return message ?? "Authentication failed"
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
