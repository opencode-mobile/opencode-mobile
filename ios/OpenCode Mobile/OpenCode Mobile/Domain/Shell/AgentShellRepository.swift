import Foundation

/// Agent-run shells of the open session, whatever the server protocol. See `AgentShellRemote`.
nonisolated protocol AgentShellRepository: AgentShellRemote {}

nonisolated struct DefaultAgentShellRepository: AgentShellRepository {
    private let remote: ProtocolSelectedAgentShellRemote

    init(
        api: ShellAPIClient,
        eventStream: any ForegroundEventStream,
        activeConnectionManager: ActiveConnectionManager?
    ) {
        self.init(
            v1: V1AgentShellRemote(),
            v2: V2AgentShellRemote(api: api, eventStream: eventStream),
            activeConnectionManager: activeConnectionManager
        )
    }

    init(v1: any AgentShellRemote, v2: any AgentShellRemote, activeConnectionManager: ActiveConnectionManager?) {
        self.remote = ProtocolSelectedAgentShellRemote(v1: v1, v2: v2, activeConnectionManager: activeConnectionManager)
    }

    func shells(sessionID: String, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]> {
        remote.shells(sessionID: sessionID, directory: directory, transcript: transcript)
    }

    func output(shell: AgentShell, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput> {
        remote.output(shell: shell, directory: directory, transcript: transcript)
    }

    func stop(shell: AgentShell, directory: String) async throws {
        try await remote.stop(shell: shell, directory: directory)
    }
}

/// No shells: previews, the playground, and tests that do not exercise them.
nonisolated struct UnavailableAgentShellRepository: AgentShellRepository {
    private let remote = UnavailableAgentShellRemote()

    func shells(sessionID: String, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]> {
        remote.shells(sessionID: sessionID, directory: directory, transcript: transcript)
    }

    func output(shell: AgentShell, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput> {
        remote.output(shell: shell, directory: directory, transcript: transcript)
    }

    func stop(shell: AgentShell, directory: String) async throws {
        try await remote.stop(shell: shell, directory: directory)
    }
}
