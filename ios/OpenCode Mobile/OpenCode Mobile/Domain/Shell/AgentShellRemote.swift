import Foundation

/// One protocol's way to find, follow and stop the shells an agent runs for a session.
/// `transcript` yields the open session's loaded messages each time they change; only
/// protocols that derive shells from the transcript (v1) read it.
nonisolated protocol AgentShellRemote: Sendable {
    /// The session's running shells, live while iterated. A shell seen running that ends
    /// stays in the list with its end state until the server forgets it.
    func shells(sessionID: String, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]>
    /// The shell's latest output, live while iterated. Finishes once the output is gone.
    func output(shell: AgentShell, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput>
    func stop(shell: AgentShell, directory: String) async throws
}

/// Picks the remote for the active server: v1 reads the transcript, v2 the shell API
/// when its contract declares `GET /api/shell`, and a v2 server without it has no shells.
nonisolated struct ProtocolSelectedAgentShellRemote: AgentShellRemote {
    private let v1: any AgentShellRemote
    private let v2: any AgentShellRemote
    private let unavailable: any AgentShellRemote = UnavailableAgentShellRemote()
    private let activeConnectionManager: ActiveConnectionManager?

    init(v1: any AgentShellRemote, v2: any AgentShellRemote, activeConnectionManager: ActiveConnectionManager?) {
        self.v1 = v1
        self.v2 = v2
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() async -> any AgentShellRemote {
        guard let activeConnectionManager else { return v1 }
        switch await activeConnectionManager.currentProtocolOrProbe() {
        case .v1: return v1
        case .v2: return activeConnectionManager.capabilities.supportsAgentShells ? v2 : unavailable
        }
    }

    func shells(sessionID: String, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]> {
        forwarding { await selected().shells(sessionID: sessionID, directory: directory, transcript: transcript) }
    }

    func output(shell: AgentShell, directory: String, transcript: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput> {
        forwarding { await selected().output(shell: shell, directory: directory, transcript: transcript) }
    }

    func stop(shell: AgentShell, directory: String) async throws {
        try await selected().stop(shell: shell, directory: directory)
    }

    private func forwarding<T: Sendable>(_ make: @escaping @Sendable () async -> AsyncStream<T>) -> AsyncStream<T> {
        AsyncStream { continuation in
            let task = Task {
                for await value in await make() {
                    continuation.yield(value)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

/// v1: shells are the session's `bash` tool calls; output is the call's live metadata.
nonisolated struct V1AgentShellRemote: AgentShellRemote {
    func shells(sessionID: String, directory _: String, transcript: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]> {
        AsyncStream { continuation in
            let task = Task {
                var seenRunning: Set<String> = []
                var last: [AgentShell]?
                for await messages in transcript {
                    let all = V1AgentShellAdapter.shells(in: messages, sessionID: sessionID)
                    seenRunning.formUnion(all.filter(\.status.isRunning).map(\.id))
                    let visible = all.filter { seenRunning.contains($0.id) }
                    guard visible != last else { continue }
                    last = visible
                    continuation.yield(visible)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func output(shell: AgentShell, directory _: String, transcript: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput> {
        AsyncStream { continuation in
            let task = Task {
                var last: AgentShellOutput?
                for await messages in transcript {
                    guard let output = V1AgentShellAdapter.output(shellID: shell.id, in: messages), output != last else {
                        continue
                    }
                    last = output
                    continuation.yield(output)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func stop(shell _: AgentShell, directory _: String) async throws {
        throw AgentShellError.stopUnsupported
    }
}

/// v2: `GET /api/shell` on start and every reconnect, `shell.*` events in between, output
/// polled tail-first while observed, Stop through `DELETE /api/shell/{id}`.
nonisolated struct V2AgentShellRemote: AgentShellRemote {
    static let reconnectEvents: Set<String> = ["server.connected", "stream.reconnected"]

    private let api: ShellAPIClient
    private let eventStream: any ForegroundEventStream
    private let pollInterval: Duration

    init(api: ShellAPIClient, eventStream: any ForegroundEventStream, pollInterval: Duration = .seconds(1)) {
        self.api = api
        self.eventStream = eventStream
        self.pollInterval = pollInterval
    }

    func shells(sessionID: String, directory: String, transcript _: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]> {
        let api = api
        let eventStream = eventStream
        return AsyncStream { continuation in
            let task = Task {
                // Subscribe first so events sent while the list loads are not lost.
                let events = eventStream.events()
                var list = V2AgentShellList(sessionID: sessionID)
                await Self.relist(&list, api: api, directory: directory)
                continuation.yield(list.shells)
                for await event in events {
                    if Task.isCancelled { break }
                    if Self.reconnectEvents.contains(event.type) {
                        let before = list
                        await Self.relist(&list, api: api, directory: directory)
                        if list != before { continuation.yield(list.shells) }
                        continue
                    }
                    guard let change = V2AgentShellAdapter.change(from: event), list.apply(change) else { continue }
                    continuation.yield(list.shells)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func output(shell: AgentShell, directory: String, transcript _: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput> {
        let api = api
        let pollInterval = pollInterval
        return AsyncStream { continuation in
            let task = Task {
                var tail = V2AgentShellOutputTail()
                var last = AgentShellOutput.empty
                polling: while !Task.isCancelled {
                    do {
                        var more = true
                        while more, !Task.isCancelled {
                            let page = try await api.output(id: shell.id, directory: directory, cursor: tail.requestCursor)
                            more = tail.apply(page)
                        }
                        if tail.output != last {
                            last = tail.output
                            continuation.yield(last)
                        }
                    } catch let error as ShellAPIError where error.isNotFound {
                        // Removed: nothing more to read; the last output stays on screen.
                        break polling
                    } catch {
                        // A dropped request; the next poll tries again.
                    }
                    try? await Task.sleep(for: pollInterval)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func stop(shell: AgentShell, directory: String) async throws {
        try await api.delete(id: shell.id, directory: directory)
    }

    /// Replaces the list with the server's; shells that ended while the app was away are
    /// looked up once so an open sheet can show how they ended.
    private static func relist(_ list: inout V2AgentShellList, api: ShellAPIClient, directory: String) async {
        guard let listed = try? await api.list(directory: directory) else { return }
        let missing = list.replace(with: listed.compactMap(V2AgentShellAdapter.shell(from:)))
        for shell in missing {
            guard let info = try? await api.get(id: shell.id, directory: directory),
                  let fetched = V2AgentShellAdapter.shell(from: info)
            else { continue }
            list.restoreEnded(fetched)
        }
    }
}

/// A server with no shell support: no shells, no output, no Stop.
nonisolated struct UnavailableAgentShellRemote: AgentShellRemote {
    func shells(sessionID _: String, directory _: String, transcript _: AsyncStream<[Message]>) -> AsyncStream<[AgentShell]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }

    func output(shell _: AgentShell, directory _: String, transcript _: AsyncStream<[Message]>) -> AsyncStream<AgentShellOutput> {
        AsyncStream { $0.finish() }
    }

    func stop(shell _: AgentShell, directory _: String) async throws {
        throw AgentShellError.stopUnsupported
    }
}
