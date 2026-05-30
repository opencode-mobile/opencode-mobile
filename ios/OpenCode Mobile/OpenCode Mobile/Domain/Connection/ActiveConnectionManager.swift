import Foundation

final class ActiveConnectionManager: @unchecked Sendable {
    private let lock = NSLock()
    private var _connection: ServerConnection?

    var connection: ServerConnection? {
        lock.lock()
        defer { lock.unlock() }
        return _connection
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

    func apply(_ profile: ConnectionProfile) {
        let connection = ServerConnection(profile: profile)
        lock.lock()
        _connection = connection
        lock.unlock()
    }

    func applyConnection(_ connection: ServerConnection) {
        lock.lock()
        _connection = connection
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
    private let lock = NSLock()
    private var profileSavedHandlers: [() -> Void] = []

    init(
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: ConnectionProfileStore
    ) {
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
    }

    func onProfileSaved(_ handler: @escaping () -> Void) {
        lock.lock()
        profileSavedHandlers.append(handler)
        lock.unlock()
    }

    func saveProfile(_ profile: ConnectionProfile) async {
        await connectionProfileStore.saveProfile(profile)
        activeConnectionManager.apply(profile)
        lock.lock()
        let handlers = profileSavedHandlers
        lock.unlock()
        handlers.forEach { $0() }
    }
}

enum ProbeStatus: Equatable {
    case reachable
    case authFailed
    case unreachable
}

protocol ConnectionProbing: Sendable {
    func probe(connection: ServerConnection) async throws
}

struct ConnectionProbeService: ConnectionProbing, Sendable {
    private let adapter = ProjectDomainAdapter()

    func probe(connection: ServerConnection) async throws {
        let client = ProjectsAPIClient(fixedConnection: connection)
        do {
            try adapter.requireHealthy(try await client.getHealth())
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
