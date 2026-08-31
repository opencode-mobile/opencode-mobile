import Foundation

protocol TerminalRemote: Sendable {
    func list(directory: String?) async throws -> [TerminalSession]
    func create(directory: String, cwd: String) async throws -> TerminalSession
    func get(id: String, directory: String) async throws -> TerminalSession
    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession
    func remove(id: String, directory: String) async throws
}

struct ProtocolSelectedTerminalRemote: TerminalRemote, Sendable {
    private let v1: V1TerminalRemote
    private let v2: V2TerminalRemote
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        v1: V1TerminalRemote,
        v2: V2TerminalRemote,
        activeConnectionManager: ActiveConnectionManager?
    ) {
        self.v1 = v1
        self.v2 = v2
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() async -> any TerminalRemote {
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            return v2
        }
        return v1
    }

    func list(directory: String?) async throws -> [TerminalSession] {
        try await selected().list(directory: directory)
    }

    func create(directory: String, cwd: String) async throws -> TerminalSession {
        try await selected().create(directory: directory, cwd: cwd)
    }

    func get(id: String, directory: String) async throws -> TerminalSession {
        try await selected().get(id: id, directory: directory)
    }

    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession {
        try await selected().updateSize(id: id, directory: directory, cols: cols, rows: rows)
    }

    func remove(id: String, directory: String) async throws {
        try await selected().remove(id: id, directory: directory)
    }
}
