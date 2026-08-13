import Foundation

protocol CommandRepository: Sendable {
    func listCommands(directory: String?) async throws -> [ServerCommand]
    func runCommand(sessionID: String, options: CommandRunOptions, directory: String?) async throws
}

struct DefaultCommandRepository: CommandRepository, Sendable {
    private let api: CommandsAPIClient
    private let adapter: CommandDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: CommandsAPIClient,
        adapter: CommandDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func listCommands(directory: String?) async throws -> [ServerCommand] {
        do {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                return adapter.toServerCommands(try await api.listCommandsV2(directory: directory))
            }
            return adapter.toServerCommands(try await api.listCommands(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }

    func runCommand(sessionID: String, options: CommandRunOptions, directory: String?) async throws {
        do {
            let body = adapter.toRunRequest(options)
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                try await api.runCommandV2(sessionID: sessionID, body: body, directory: directory)
            } else {
                try await api.runCommand(sessionID: sessionID, body: body, directory: directory)
            }
        } catch {
            throw adapter.mapError(error)
        }
    }
}
