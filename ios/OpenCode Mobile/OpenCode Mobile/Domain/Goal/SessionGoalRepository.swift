import Foundation

/// Reads and controls a session's goal. Only v2 servers have one; on v1 `isAvailable` is false
/// and nothing is fetched.
nonisolated protocol SessionGoalRepository: Sendable {
    /// Whether the active server speaks v2, the only protocol with the goal plugin. A v2 server
    /// without the plugin still answers true; its `goal` call fails.
    func isAvailable() async -> Bool
    /// The session's goal, or nil when it has none. Throws when the call fails, as on a server
    /// without the goal plugin.
    func goal(sessionID: String, directory: String) async throws -> SessionGoal?
    /// Sends `/goal pause|resume|clear` as the session's `goal` command.
    func send(_ action: SessionGoalAction, sessionID: String, directory: String) async throws
}

nonisolated struct DefaultSessionGoalRepository: SessionGoalRepository {
    private let api: GoalAPIClient
    private let commandRepository: any CommandRepository
    private let serverProtocol: @Sendable () async -> ServerProtocol?

    init(
        api: GoalAPIClient,
        commandRepository: any CommandRepository,
        activeConnectionManager: ActiveConnectionManager?
    ) {
        self.init(
            api: api,
            commandRepository: commandRepository,
            serverProtocol: { await activeConnectionManager?.currentProtocolOrProbe() }
        )
    }

    init(
        api: GoalAPIClient,
        commandRepository: any CommandRepository,
        serverProtocol: @escaping @Sendable () async -> ServerProtocol?
    ) {
        self.api = api
        self.commandRepository = commandRepository
        self.serverProtocol = serverProtocol
    }

    func isAvailable() async -> Bool {
        await serverProtocol() == .v2
    }

    func goal(sessionID: String, directory: String) async throws -> SessionGoal? {
        try SessionGoalAdapter.goal(fromRPCOutput: try await api.get(sessionID: sessionID, directory: directory))
    }

    func send(_ action: SessionGoalAction, sessionID: String, directory: String) async throws {
        try await commandRepository.runCommand(
            sessionID: sessionID,
            options: CommandRunOptions(command: SessionGoalKickoff.commandName, arguments: action.commandText),
            directory: directory
        )
    }
}

/// No goal: v1-only builds, previews, the playground and tests that do not exercise it.
nonisolated struct UnavailableSessionGoalRepository: SessionGoalRepository {
    func isAvailable() async -> Bool { false }
    func goal(sessionID _: String, directory _: String) async throws -> SessionGoal? { nil }
    func send(_: SessionGoalAction, sessionID _: String, directory _: String) async throws {}
}
