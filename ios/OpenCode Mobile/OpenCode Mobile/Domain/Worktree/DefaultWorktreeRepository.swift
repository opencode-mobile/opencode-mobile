import Foundation

struct DefaultWorktreeRepository: WorktreeRepository {
    private let remote: any WorktreeRemote
    private let protocolProvider: @Sendable () async -> ServerProtocol
    private let state = WorktreeProbeState()

    init(remote: any WorktreeRemote, protocolProvider: @escaping @Sendable () async -> ServerProtocol) {
        self.remote = remote
        self.protocolProvider = protocolProvider
    }

    init(api: WorktreesAPIClient, activeConnectionManager: ActiveConnectionManager) {
        self.init(
            remote: api,
            protocolProvider: { await activeConnectionManager.currentProtocolOrProbe() }
        )
    }

    func load(addedPath: String, projectID: String?) async -> WorktreeLoad {
        if await protocolProvider() == .v2 {
            return await loadV2(projectID)
        }
        return await loadV1(addedPath)
    }

    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad {
        let listed = await load(addedPath: addedPath, projectID: projectID)
        guard case let .loaded(_, listsSessions) = listed, !listsSessions, let projectID, !projectID.isEmpty else {
            return listed
        }
        guard await refresh(projectID: projectID) else { return listed }
        let refreshed = await refetch(projectID: projectID)
        switch refreshed {
        case .loaded, .failed:
            return refreshed
        default:
            return listed
        }
    }

    func refresh(projectID: String) async -> Bool {
        guard await protocolProvider() == .v2, !projectID.isEmpty else { return false }
        do {
            try await remote.refreshV2(projectID: projectID)
            return true
        } catch is CancellationError {
            return false
        } catch {
            return false
        }
    }

    func refetch(projectID: String) async -> WorktreeLoad {
        guard await protocolProvider() == .v2 else { return .unloaded }
        return await loadV2(projectID)
    }

    func onDisconnected() {
        state.onDisconnected()
    }

    func onConnection(key: String) {
        state.onConnection(key)
    }

    private func loadV2(_ projectID: String?) async -> WorktreeLoad {
        guard let projectID, !projectID.isEmpty else { return .unloaded }
        do {
            return .loaded(directories: try await remote.listV2(projectID: projectID), listsSessions: false)
        } catch is CancellationError {
            return .failed
        } catch {
            return .failed
        }
    }

    private func loadV1(_ addedPath: String) async -> WorktreeLoad {
        if state.isStopped { return .stopped }
        switch await remote.listV1(directory: addedPath) {
        case .notFound:
            state.markStopped()
            return .stopped
        case .failed:
            return .failed
        case let .ok(body):
            guard let directories = V1WorktreeListParser.parse(body) else { return .failed }
            return .loaded(directories: directories, listsSessions: true)
        }
    }
}

/// Connection-scoped V1 404 stop. A class so the repository value can mutate it.
private final class WorktreeProbeState: @unchecked Sendable {
    private let lock = NSLock()
    private var connectionKey: String?
    private var stoppedKey: String?

    var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stoppedKey != nil && stoppedKey == connectionKey
    }

    func onDisconnected() {
        lock.lock()
        connectionKey = nil
        stoppedKey = nil
        lock.unlock()
    }

    func onConnection(_ key: String) {
        lock.lock()
        if connectionKey != key {
            stoppedKey = nil
            connectionKey = key
        }
        lock.unlock()
    }

    func markStopped() {
        lock.lock()
        stoppedKey = connectionKey
        lock.unlock()
    }
}
