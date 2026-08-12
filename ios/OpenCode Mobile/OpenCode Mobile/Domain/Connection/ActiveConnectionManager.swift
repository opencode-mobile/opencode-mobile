import Foundation

final class ActiveConnectionManager: @unchecked Sendable {
    private let lock = NSLock()
    private var _connection: ServerConnection?

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
        lock.unlock()
    }

    func applyConnection(_ connection: ServerConnection) {
        lock.lock()
        _connection = connection
        lock.unlock()
    }

    func updateProtocol(_ serverProtocol: ServerProtocol) {
        lock.lock()
        defer { lock.unlock() }
        guard let current = _connection else { return }
        _connection = current.withProtocol(serverProtocol)
    }

    func disconnect() {
        lock.lock()
        _connection = nil
        lock.unlock()
    }

    func currentProfile() -> ConnectionProfile? {
        guard let connection = connection else { return nil }
        return ConnectionProfile(
            baseURL: connection.baseURL.absoluteString,
            username: connection.username,
            password: connection.password ?? ""
        )
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
        await connectionProfileStore.saveProfile(profile)
        activeConnectionManager.apply(profile, serverProtocol: serverProtocol)
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
    private let adapter = ProjectDomainAdapter()
    private let urlSession: URLSession

    init(urlSession: URLSession = .shared) {
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
