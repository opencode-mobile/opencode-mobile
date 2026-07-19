import Foundation

protocol CommandRepository: Sendable {
    func listCommands(directory: String?) async throws -> [ServerCommand]
    func runCommand(sessionID: String, options: CommandRunOptions, directory: String?) async throws
}

struct DefaultCommandRepository: CommandRepository, Sendable {
    private let api: CommandsAPIClient
    private let adapter: CommandDomainAdapter

    init(api: CommandsAPIClient, adapter: CommandDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listCommands(directory: String?) async throws -> [ServerCommand] {
        do {
            return adapter.toServerCommands(try await api.listCommands(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }

    func runCommand(sessionID: String, options: CommandRunOptions, directory: String?) async throws {
        do {
            try await api.runCommand(
                sessionID: sessionID,
                body: adapter.toRunRequest(options),
                directory: directory
            )
        } catch {
            throw adapter.mapError(error)
        }
    }
}
