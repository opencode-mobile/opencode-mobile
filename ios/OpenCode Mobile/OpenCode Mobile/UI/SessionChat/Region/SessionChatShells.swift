import Foundation

/// The open shell output sheet.
struct SessionChatShellOutputState: Equatable {
    /// The shell as last seen; it keeps its end state after the shell leaves the running list.
    var shell: AgentShell
    var output: AgentShellOutput = .empty
    /// Stop shell was tapped; stays on until the shell ends or the request fails.
    var isStopping = false
    var stopFailed = false

    var canStop: Bool { shell.canStop && shell.status.isRunning }
}

struct SessionChatShellsRegionState: Equatable {
    /// Shells of the open session seen running, with the end state of those that ended.
    var shells: [AgentShell] = []
    var output: SessionChatShellOutputState?
}

/// Session Chat region for the shells the agent runs: the live list from the shell
/// repository (whatever the protocol), the output sheet and Stop shell.
///
/// The list follows the session while the screen observes. Output is read only while
/// the view watches it (the sheet is up and the app is active), see `watchOutput()`.
@MainActor
final class SessionChatShells {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatShellsRegionState()

    private var sessionID: String
    private let worktree: String
    private let repository: any AgentShellRepository
    private let transcript = SessionChatTranscriptFeed()
    private var listTask: Task<Void, Never>?
    /// Tells a finished list task from the one that replaced it.
    private var listGeneration = 0
    private var stopTask: Task<Void, Never>?
    private let nowMs: @MainActor () -> Int64

    init(
        sessionID: String,
        worktree: String,
        repository: any AgentShellRepository,
        nowMs: @escaping @MainActor () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.repository = repository
        self.nowMs = nowMs
    }

    func start() {
        guard listTask == nil, !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        let stream = repository.shells(sessionID: sessionID, directory: worktree, transcript: transcriptSubscription)
        listGeneration += 1
        let generation = listGeneration
        listTask = Task { [weak self] in
            for await shells in stream {
                guard let self, !Task.isCancelled else { return }
                self.apply(shells)
            }
            // A list that ended on its own lets the next `start()` subscribe again.
            guard let self, self.listGeneration == generation else { return }
            self.listTask = nil
        }
    }

    func stop() {
        listTask?.cancel()
        listTask = nil
    }

    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        stop()
        self.sessionID = sessionID
        state = SessionChatShellsRegionState()
        onChange?()
    }

    /// The open session's loaded messages changed; transcript-backed shells follow them.
    func transcriptChanged(_ messages: [Message]) {
        transcript.publish(messages)
    }

    var transcriptSubscriberCount: Int { transcript.subscriberCount }

    func openOutput(shellID: String) {
        guard let shell = state.shells.first(where: { $0.id == shellID }) else { return }
        if state.output?.shell.id == shellID { return }
        stopTask?.cancel()
        state.output = SessionChatShellOutputState(shell: shell)
        onChange?()
    }

    func closeOutput() {
        guard state.output != nil else { return }
        stopTask?.cancel()
        stopTask = nil
        state.output = nil
        onChange?()
    }

    /// Reads the open shell's output until cancelled or the output is gone. The view runs
    /// it while the sheet is up and the app is active, so nothing polls in the background.
    func watchOutput() async {
        guard let shell = state.output?.shell else { return }
        let stream = repository.output(shell: shell, directory: worktree, transcript: transcriptSubscription)
        for await output in stream {
            guard !Task.isCancelled else { return }
            guard state.output?.shell.id == shell.id, state.output?.output != output else { continue }
            state.output?.output = output
            onChange?()
        }
        // The output ended on its own: the shell is gone (v2 404). Keep the last text.
        guard !Task.isCancelled, state.output?.shell.id == shell.id else { return }
        endOpenShellLocally()
    }

    func stopShell(shellID: String) {
        guard let output = state.output, output.shell.id == shellID, output.canStop, !output.isStopping else { return }
        state.output?.isStopping = true
        state.output?.stopFailed = false
        onChange?()
        let shell = output.shell
        let repository = repository
        let worktree = worktree
        stopTask = Task { [weak self] in
            do {
                try await repository.stop(shell: shell, directory: worktree)
                // The sheet shows "Stopped" when the server reports the shell ended.
            } catch {
                guard !Task.isCancelled, let self, self.state.output?.shell.id == shellID else { return }
                self.state.output?.isStopping = false
                self.state.output?.stopFailed = true
                self.onChange?()
            }
        }
    }

    /// Subscribes to the transcript only when a remote reads it (v1).
    private var transcriptSubscription: AgentShellTranscript {
        let feed = transcript
        return { await feed.stream() }
    }

    private func apply(_ shells: [AgentShell]) {
        var next = state
        next.shells = shells
        if var output = next.output {
            if let latest = shells.first(where: { $0.id == output.shell.id }) {
                output.shell = latest
                if !latest.status.isRunning { output.isStopping = false }
            } else if output.shell.status.isRunning {
                // Gone without an end event (missed while away, or removed): it ended.
                output = Self.endedLocally(output, atMs: nowMs())
            }
            next.output = output
        }
        guard next != state else { return }
        state = next
        onChange?()
    }

    private func endOpenShellLocally() {
        guard let output = state.output, output.shell.status.isRunning else { return }
        state.output = Self.endedLocally(output, atMs: nowMs())
        onChange?()
    }

    /// Stopped when Stop was in flight, else exited with an unknown code.
    private static func endedLocally(_ output: SessionChatShellOutputState, atMs: Int64) -> SessionChatShellOutputState {
        var next = output
        next.shell = output.shell.ended(output.isStopping ? .stopped : .exited(code: nil), atMs: atMs)
        next.shell.finishedAtMs = atMs
        next.isStopping = false
        return next
    }
}

/// Fans the latest loaded transcript out to every shell stream that reads it; each new
/// stream starts with the current transcript.
@MainActor
final class SessionChatTranscriptFeed {
    private var latest: [Message]?
    private var continuations: [UUID: AsyncStream<[Message]>.Continuation] = [:]

    /// Open subscriptions; only transcript-backed remotes (v1) hold one.
    var subscriberCount: Int { continuations.count }

    func stream() -> AsyncStream<[Message]> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: [Message].self, bufferingPolicy: .bufferingNewest(1))
        continuations[id] = continuation
        if let latest { continuation.yield(latest) }
        continuation.onTermination = { [weak self] _ in
            guard let feed = self else { return }
            Task { @MainActor in feed.continuations[id] = nil }
        }
        return stream
    }

    func publish(_ messages: [Message]) {
        latest = messages
        for continuation in continuations.values {
            continuation.yield(messages)
        }
    }
}
