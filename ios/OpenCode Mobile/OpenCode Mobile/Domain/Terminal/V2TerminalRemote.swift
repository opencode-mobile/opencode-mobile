import Foundation

struct V2TerminalRemote: TerminalRemote, Sendable {
    private let api: PtyAPIClient
    private let adapter: TerminalDomainAdapter

    init(api: PtyAPIClient, adapter: TerminalDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func list(directory: String) async throws -> [TerminalSession] {
        try await api.listV2(directory: directory).map { adapter.session(from: $0) }
    }

    func create(directory: String, cwd: String) async throws -> TerminalSession {
        adapter.session(from: try await api.createV2(body: CreatePtyRequestDTO(cwd: cwd), directory: directory))
    }

    func get(id: String, directory: String) async throws -> TerminalSession {
        adapter.session(from: try await api.getV2(id: id, directory: directory))
    }

    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession {
        adapter.session(
            from: try await api.updateV2(
                id: id,
                body: UpdatePtyRequestDTO(size: PtySizeDTO(rows: rows, cols: cols)),
                directory: directory
            )
        )
    }

    func remove(id: String, directory: String) async throws {
        try await api.deleteV2(id: id, directory: directory)
    }
}
