import Foundation

protocol TerminalRepository: Sendable {
    func list(directory: String) async throws -> [TerminalSession]
    func create(directory: String, cwd: String) async throws -> TerminalSession
    func get(id: String, directory: String) async throws -> TerminalSession
    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession
    func ensureRunning(directory: String) async throws -> TerminalSession
    func connectRequest(
        id: String,
        directory: String,
        serverProtocol: ServerProtocol,
        baseURL: String,
        cursor: String,
        ticket: String?,
        username: String,
        password: String
    ) -> TerminalConnectRequest
}

extension TerminalRepository {
    func create(directory: String) async throws -> TerminalSession {
        try await create(directory: directory, cwd: directory)
    }
}

struct DefaultTerminalRepository: TerminalRepository, Sendable {
    private let remote: any TerminalRemote
    private let adapter: TerminalDomainAdapter

    init(
        api: PtyAPIClient,
        adapter: TerminalDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.remote = ProtocolSelectedTerminalRemote(
            v1: V1TerminalRemote(api: api, adapter: adapter),
            v2: V2TerminalRemote(api: api, adapter: adapter),
            activeConnectionManager: activeConnectionManager
        )
        self.adapter = adapter
    }

    func list(directory: String) async throws -> [TerminalSession] {
        try await run { try await remote.list(directory: directory) }
    }

    func create(directory: String, cwd: String) async throws -> TerminalSession {
        try await run { try await remote.create(directory: directory, cwd: cwd) }
    }

    func get(id: String, directory: String) async throws -> TerminalSession {
        try await run { try await remote.get(id: id, directory: directory) }
    }

    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession {
        try await run { try await remote.updateSize(id: id, directory: directory, cols: cols, rows: rows) }
    }

    func ensureRunning(directory: String) async throws -> TerminalSession {
        let sessions = try await list(directory: directory)
        if let running = sessions.first(where: { $0.status == .running }) {
            return running
        }
        return try await create(directory: directory, cwd: directory)
    }

    func connectRequest(
        id: String,
        directory: String,
        serverProtocol: ServerProtocol,
        baseURL: String,
        cursor: String,
        ticket: String?,
        username: String,
        password: String
    ) -> TerminalConnectRequest {
        TerminalConnectRequest.build(
            id: id,
            directory: directory,
            serverProtocol: serverProtocol,
            baseURL: baseURL,
            cursor: cursor,
            ticket: ticket,
            username: username,
            password: password
        )
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapError(error)
        }
    }
}
