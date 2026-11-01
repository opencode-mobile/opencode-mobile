import Foundation

/// Everything the Session Chat Playground pretends a server holds, as the same domain
/// types the server adapters produce. The playground repositories read it; scenario
/// scripts and fake server actions change it.
struct PlaygroundState: Equatable {
    var rootSessionID: String
    var sessions: [Session]
    var messages: [String: [Message]] = [:]
    /// Sessions at work; every other session is idle.
    var activity: [String: SessionActivity] = [:]
    var permissions: [PermissionRequest] = []
    var questions: [QuestionRequest] = []
    /// Todos of the root session.
    var todos: [SessionTodo] = []
    /// Current branch; nil hides the branch pill.
    var branch: String?
    var defaultBranch: String? = "main"
    var changedFiles: [VcsFileStatus] = []
    var turnDiff: [FileDiff] = []
    /// What a `/btw` side question answers.
    var sideAnswer = PlaygroundContent.sideAnswer

    func messages(_ sessionID: String) -> [Message] { messages[sessionID] ?? [] }

    func session(_ id: String) -> Session? { sessions.first { $0.id == id } }

    func activity(_ sessionID: String) -> SessionActivity { activity[sessionID] ?? .idle }

    func isBusy(_ sessionID: String) -> Bool { activity(sessionID).isBusy }

    func withActivity(_ sessionID: String, _ activity: SessionActivity) -> PlaygroundState {
        var copy = self
        copy.activity[sessionID] = activity.isBusy ? activity : nil
        return copy
    }

    /// Adds `message`, or replaces the one with its id.
    func upsert(_ sessionID: String, _ message: Message) -> PlaygroundState {
        var copy = self
        var list = messages(sessionID)
        if let index = list.firstIndex(where: { $0.id == message.id }) {
            list[index] = message
        } else {
            list.append(message)
        }
        copy.messages[sessionID] = list
        return copy
    }

    func mapMessages(_ sessionID: String, _ transform: (Message) -> Message) -> PlaygroundState {
        var copy = self
        copy.messages[sessionID] = messages(sessionID).map(transform)
        return copy
    }

    func removingMessage(_ sessionID: String, _ messageID: String) -> PlaygroundState {
        var copy = self
        copy.messages[sessionID] = messages(sessionID).filter { $0.id != messageID }
        return copy
    }

    /// Stops `sessionID` the way an abort does: streaming ends, running tools end interrupted.
    func aborted(_ sessionID: String) -> PlaygroundState {
        withActivity(sessionID, .idle).mapMessages(sessionID) { message in
            guard message.role == .assistant else { return message }
            return message.replacing(parts: message.parts.map { part in
                switch part {
                case var .text(text):
                    text.isStreaming = false
                    return .text(text)
                case var .reasoning(reasoning):
                    reasoning.isStreaming = false
                    return .reasoning(reasoning)
                case let .tool(tool) where tool.status == .running || tool.status == .pending:
                    return .tool(tool.replacing(status: .error, error: "Tool execution aborted", isInterrupted: true))
                default:
                    return part
                }
            })
        }
    }
}

/// The playground's in-memory "server": one `PlaygroundState`, streams of it for the
/// repositories, and `notice` for actions it cannot act on.
final class PlaygroundWorld {
    private(set) var state: PlaygroundState
    let now: () -> Int64
    /// Called with a short "Playground: … is not available" line.
    var onNotice: ((String) -> Void)?

    private var observers: [UUID: (PlaygroundState) -> Void] = [:]
    private var tasks: [Task<Void, Never>] = []

    init(_ initial: PlaygroundState, now: @escaping () -> Int64 = PlaygroundWorld.systemNow) {
        state = initial
        self.now = now
    }

    nonisolated static func systemNow() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func update(_ transform: (PlaygroundState) -> PlaygroundState) {
        let next = transform(state)
        guard next != state else { return }
        state = next
        for observer in observers.values { observer(next) }
    }

    /// The current value of `select`, then each change of it.
    func stream<Value: Equatable>(_ select: @escaping (PlaygroundState) -> Value) -> AsyncStream<Value> {
        AsyncStream { continuation in
            let id = UUID()
            var last = select(state)
            continuation.yield(last)
            observers[id] = { state in
                let value = select(state)
                guard value != last else { return }
                last = value
                continuation.yield(value)
            }
            let remove: @MainActor () -> Void = { [weak self] in self?.observers[id] = nil }
            continuation.onTermination = { _ in
                Task { @MainActor in remove() }
            }
        }
    }

    func notice(_ action: String) {
        onNotice?("Playground: \(action) is not available")
    }

    /// Runs `block` after `delayMs` unless the playground closes first.
    func later(_ delayMs: Int64, _ block: @escaping @MainActor () -> Void) {
        tasks.append(Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(delayMs))
            guard !Task.isCancelled else { return }
            block()
        })
    }

    /// A prompt sent from the composer: it shows, the session works briefly, then a canned reply lands.
    func acceptPrompt(sessionID: String, text: String, messageID: String?) {
        let sentAt = now()
        let userID = messageID ?? "pg-user-\(sentAt)"
        let replyID = "pg-reply-\(sentAt)"
        update { $0.upsert(sessionID, PlaygroundContent.userMessage(userID, text, sentAt)).withActivity(sessionID, .running) }
        later(Self.replyDelayMs) { [weak self] in
            guard let self else { return }
            update { state in
                guard state.isBusy(sessionID) else { return state }
                return state.upsert(sessionID, PlaygroundContent.cannedReply(replyID, now: self.now())).withActivity(sessionID, .idle)
            }
        }
    }

    func close() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        observers.removeAll()
        onNotice = nil
    }

    static let replyDelayMs: Int64 = 1_500
}

nonisolated extension Message {
    /// The reply text the parts add up to, as the server adapters join it.
    static func joinedText(_ parts: [MessagePart]) -> String {
        parts.compactMap { part -> String? in
            if case let .text(text) = part { return text.text }
            return nil
        }.joined(separator: "\n\n")
    }

    /// A copy with some fields replaced; the domain type itself is immutable.
    func replacing(
        parts: [MessagePart]? = nil,
        completedAtEpochMs: Int64?? = nil,
        followUpDelivery: FollowUpDelivery?? = nil,
        isWaitingForPickup: Bool? = nil
    ) -> Message {
        let newParts = parts ?? self.parts
        return Message(
            id: id,
            role: role,
            text: parts == nil ? text : Message.joinedText(newParts),
            parts: newParts,
            cost: cost,
            tokens: tokens,
            model: model,
            modelProviderID: modelProviderID,
            modelID: modelID,
            variant: variant,
            mode: mode,
            sentAtEpochMs: sentAtEpochMs,
            completedAtEpochMs: completedAtEpochMs ?? self.completedAtEpochMs,
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate,
            followUpDelivery: followUpDelivery ?? self.followUpDelivery,
            isWaitingForPickup: isWaitingForPickup ?? self.isWaitingForPickup,
            stopReason: stopReason,
            failure: failure,
            promptSkills: promptSkills
        )
    }
}

nonisolated extension ToolPart {
    func replacing(status: ToolStatus, output: String? = nil, error: String? = nil, isInterrupted: Bool = false, endMs: Int64? = nil) -> ToolPart {
        ToolPart(
            id: id,
            toolName: toolName,
            status: status,
            title: title,
            error: error ?? self.error,
            callID: callID,
            input: input,
            output: output ?? self.output,
            metadata: metadata,
            timeStartEpochMs: timeStartEpochMs,
            timeEndEpochMs: endMs ?? timeEndEpochMs ?? timeStartEpochMs.map { $0 + 1_800 },
            isInterrupted: isInterrupted,
            isMalformed: isMalformed,
            images: images
        )
    }
}
