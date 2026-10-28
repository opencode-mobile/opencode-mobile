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

    var canCreateWorktrees: Bool {
        !state.isStopped
    }

    func create(_ request: CreateWorktreeRequest) async throws -> CreatedWorktree {
        let name = request.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = ServerPathPolicy.canonical(request.sourceDirectory)
        if await protocolProvider() == .v2 {
            let projectID = request.projectID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !projectID.isEmpty, projectID != HomeProjectGrouping.globalProjectID else {
                throw WorktreeCreateError(message: WorktreeCreateCopy.noCommit)
            }
            do {
                let directory = ServerPathPolicy.canonical(
                    try await remote.createV2(projectID: projectID, from: source, name: name, branch: request.baseRef)
                )
                return CreatedWorktree(directory: directory, name: ServerPathPolicy.name(directory), branch: nil)
            } catch {
                throw Self.createError(error)
            }
        }
        do {
            let created = try await remote.createV1(directory: source, name: name)
            let directory = ServerPathPolicy.canonical(created.directory)
            let createdName = created.name.trimmingCharacters(in: .whitespacesAndNewlines)
            return CreatedWorktree(
                directory: directory,
                name: createdName.isEmpty ? ServerPathPolicy.name(directory) : createdName,
                branch: HomeProjectGrouping.usableBranch(created.branch)
            )
        } catch WorktreeRemoteError.http(404, _) {
            // No experimental worktree routes on this server: stop listing and creating.
            state.markStopped()
            throw WorktreeCreateError(message: WorktreeCreateCopy.unsupported)
        } catch {
            throw Self.createError(error)
        }
    }

    func baseBranches(directory: String) async throws -> [String] {
        guard await protocolProvider() == .v2 else { return [] }
        return try await remote.branchesV2(directory: ServerPathPolicy.canonical(directory))
    }

    /// The server's own words, else its raw body, else the status.
    static func createError(_ error: Error) -> Error {
        if error is CancellationError || error is WorktreeCreateError { return error }
        guard case let WorktreeRemoteError.http(statusCode, body) = error else {
            return WorktreeCreateError(message: error.localizedDescription)
        }
        if let message = ServerErrorMessage.bodyMessage(body) {
            return WorktreeCreateError(message: message)
        }
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return WorktreeCreateError(message: trimmed) }
        return WorktreeCreateError(message: WorktreeCreateCopy.requestFailed(statusCode))
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
