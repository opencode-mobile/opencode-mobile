import Foundation

/// Subscribes to the open session's loaded messages; each new transcript is yielded as the
/// messages change. Only remotes that read the transcript (v1) call it, so other protocols
/// never hold a subscription.
typealias AgentShellTranscript = @Sendable () async -> AsyncStream<[Message]>

/// One protocol's way to find, follow and stop the shells an agent runs for a session.
nonisolated protocol AgentShellRemote: Sendable {
    /// The session's running shells, live while iterated. A shell seen running that ends
    /// stays in the list with its end state until the server forgets it.
    func shells(sessionID: String, directory: String, transcript: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]>
    /// The shell's latest output, live while iterated. Finishes once the output is gone.
    func output(shell: AgentShell, directory: String, transcript: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput>
    func stop(shell: AgentShell, directory: String) async throws
}

/// Picks the remote for the active server: v1 reads the transcript, v2 the shell API
/// when its contract declares `GET /api/shell`, and a v2 server without it has no shells.
///
/// The shell list picks again on every stream (re)connect: on a cold start the v2 contract
/// is read only after the screen bound, so the first pick can be "no shells".
nonisolated struct ProtocolSelectedAgentShellRemote: AgentShellRemote {
    enum Source: Equatable, Sendable {
        case v1
        case v2
        case unavailable
    }

    static let reconnectEvents: Set<String> = ["server.connected", "stream.reconnected"]

    private let v1: any AgentShellRemote
    private let v2: any AgentShellRemote
    private let unavailable: any AgentShellRemote = UnavailableAgentShellRemote()
    private let activeConnectionManager: ActiveConnectionManager?
    private let eventStream: (any ForegroundEventStream)?

    init(
        v1: any AgentShellRemote,
        v2: any AgentShellRemote,
        activeConnectionManager: ActiveConnectionManager?,
        eventStream: (any ForegroundEventStream)? = nil
    ) {
        self.v1 = v1
        self.v2 = v2
        self.activeConnectionManager = activeConnectionManager
        self.eventStream = eventStream
    }

    private func source() async -> Source {
        guard let activeConnectionManager else { return .v1 }
        switch await activeConnectionManager.currentProtocolOrProbe() {
        case .v1: return .v1
        case .v2:
            let supported = await MainActor.run { activeConnectionManager.capabilities.supportsAgentShells }
            return supported ? .v2 : .unavailable
        }
    }

    private func remote(for source: Source) -> any AgentShellRemote {
        switch source {
        case .v1: v1
        case .v2: v2
        case .unavailable: unavailable
        }
    }

    func shells(sessionID: String, directory: String, transcript: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]> {
        let eventStream = eventStream
        return AsyncStream { continuation in
            let task = Task {
                // Subscribe first so a reconnect during the first pick is not missed.
                let events = eventStream?.events()
                var current = await source()
                var inner = forward(remote(for: current).shells(sessionID: sessionID, directory: directory, transcript: transcript), to: continuation)
                if let events {
                    for await event in events {
                        guard Self.reconnectEvents.contains(event.type) else { continue }
                        let next = await source()
                        guard next != current else { continue }
                        inner.cancel()
                        current = next
                        inner = forward(remote(for: next).shells(sessionID: sessionID, directory: directory, transcript: transcript), to: continuation)
                    }
                } else {
                    let only = inner
                    await withTaskCancellationHandler {
                        await only.value
                    } onCancel: {
                        only.cancel()
                    }
                }
                inner.cancel()
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func output(shell: AgentShell, directory: String, transcript: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput> {
        AsyncStream { continuation in
            let task = Task {
                let stream = remote(for: await source()).output(shell: shell, directory: directory, transcript: transcript)
                for await value in stream {
                    continuation.yield(value)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func stop(shell: AgentShell, directory: String) async throws {
        try await remote(for: await source()).stop(shell: shell, directory: directory)
    }

    /// Copies `stream` into `continuation` without finishing it, so another source can take over.
    private func forward<T: Sendable>(_ stream: AsyncStream<T>, to continuation: AsyncStream<T>.Continuation) -> Task<Void, Never> {
        Task {
            for await value in stream {
                continuation.yield(value)
            }
        }
    }
}

/// v1: shells are the session's `bash` tool calls; output is the call's live metadata.
nonisolated struct V1AgentShellRemote: AgentShellRemote {
    func shells(sessionID: String, directory _: String, transcript: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]> {
        AsyncStream { continuation in
            let task = Task {
                var seenRunning: Set<String> = []
                var last: [AgentShell]?
                for await messages in await transcript() {
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

    func output(shell: AgentShell, directory _: String, transcript: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput> {
        AsyncStream { continuation in
            let task = Task {
                var last: AgentShellOutput?
                for await messages in await transcript() {
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

    func shells(sessionID: String, directory: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]> {
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

    func output(shell: AgentShell, directory: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput> {
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
    func shells(sessionID _: String, directory _: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }

    func output(shell _: AgentShell, directory _: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput> {
        AsyncStream { $0.finish() }
    }

    func stop(shell _: AgentShell, directory _: String) async throws {
        throw AgentShellError.stopUnsupported
    }
}
