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
    private var stopTask: Task<Void, Never>?

    init(sessionID: String, worktree: String, repository: any AgentShellRepository) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.repository = repository
    }

    func start() {
        guard listTask == nil, !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        let stream = repository.shells(sessionID: sessionID, directory: worktree, transcript: transcript.stream())
        listTask = Task { [weak self] in
            for await shells in stream {
                guard let self, !Task.isCancelled else { return }
                self.apply(shells)
            }
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
        let stream = repository.output(shell: shell, directory: worktree, transcript: transcript.stream())
        for await output in stream {
            guard !Task.isCancelled else { return }
            guard state.output?.shell.id == shell.id, state.output?.output != output else { continue }
            state.output?.output = output
            onChange?()
        }
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

    private func apply(_ shells: [AgentShell]) {
        var next = state
        next.shells = shells
        if var output = next.output, let latest = shells.first(where: { $0.id == output.shell.id }) {
            output.shell = latest
            if !latest.status.isRunning { output.isStopping = false }
            next.output = output
        }
        guard next != state else { return }
        state = next
        onChange?()
    }
}

/// Fans the latest loaded transcript out to every shell stream that reads it; each new
/// stream starts with the current transcript.
@MainActor
final class SessionChatTranscriptFeed {
    private var latest: [Message]?
    private var continuations: [UUID: AsyncStream<[Message]>.Continuation] = [:]

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
