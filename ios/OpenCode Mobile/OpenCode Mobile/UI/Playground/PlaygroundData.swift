import Foundation

// The playground's data sources: the same repository and live-store protocols the server
// adapters implement, answered from a `PlaygroundWorld`. No network, no persistence.

private let notInPlayground = SessionError.http(statusCode: 501, message: "Not available in the Playground")

/// The fake connection the playground binds; `example.com` never resolves to anything it calls.
let playgroundProfile = ConnectionProfile(
    baseURL: "https://playground.example.com/",
    username: "opencode",
    password: "",
    serverProtocol: .v2,
    id: "playground",
    name: "Playground"
)

/// A streaming frame cancels the history pass that renders markdown; let it finish first.
private let historySettleMs: Int64 = 600

final class PlaygroundMessageRepository: MessageRepository, DisplayMessageSnapshotSource {
    private let world: PlaygroundWorld
    private let preparer: MessageContentPreparer
    private lazy var pipeline = DisplayMessagePipeline(snapshotSource: self, contentPreparer: preparer)

    init(world: PlaygroundWorld, preparer: MessageContentPreparer = .shared) {
        self.world = world
        self.preparer = preparer
    }

    func observeMessages(sessionID: String, directory _: String?) -> AsyncStream<MessageListUpdate> {
        let live = world.stream { $0.messages(sessionID) }
        let history = world.state.messages(sessionID)
        let settled = history.map(Self.settled)
        return AsyncStream { continuation in
            // History first, as a settled frame: the display pipeline renders markdown for what it
            // saw finished, and a session opened mid-stream would otherwise show raw history.
            continuation.yield(.messages(settled, isFromCache: false))
            let task = Task { @MainActor in
                if settled != history { try? await Task.sleep(for: .milliseconds(historySettleMs)) }
                for await messages in live {
                    continuation.yield(.messages(messages, isFromCache: false))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        pipeline.observeDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func refreshMessages(sessionID: String, directory _: String?) async throws -> [Message] {
        world.state.messages(sessionID)
    }

    func refreshDisplayMessages(sessionID: String, directory _: String?) async throws -> [DisplayMessage] {
        await preparer.prepareMessages(world.state.messages(sessionID), previousByID: [:], mode: .final)
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await preparer.prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory _: String?) async throws -> [Message] {
        world.state.messages(sessionID)
    }

    func getMessage(sessionID: String, messageID: String, directory _: String?) async throws -> Message {
        guard let message = world.state.messages(sessionID).first(where: { $0.id == messageID }) else { throw notInPlayground }
        return message
    }

    func sendMessage(sessionID: String, text: String, directory _: String?) async throws -> Message {
        world.acceptPrompt(sessionID: sessionID, text: text, messageID: nil)
        return world.state.messages(sessionID).last!
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory _: String?) async throws {
        world.acceptPrompt(sessionID: sessionID, text: options.text, messageID: options.messageID)
    }

    func runShellCommand(sessionID _: String, options _: ShellCommandOptions, directory _: String?) async throws {
        world.notice("Shell commands")
    }

    nonisolated private static func settled(_ message: Message) -> Message {
        message.replacing(parts: message.parts.map { part in
            switch part {
            case var .text(text):
                text.isStreaming = false
                return .text(text)
            case var .reasoning(reasoning):
                reasoning.isStreaming = false
                return .reasoning(reasoning)
            default:
                return part
            }
        })
    }
}

final class PlaygroundSessionRepository: SessionRepository {
    private let world: PlaygroundWorld
    private var state: PlaygroundState { world.state }

    init(world: PlaygroundWorld) {
        self.world = world
    }

    private func unavailable<T>(_ action: String) throws -> T {
        world.notice(action)
        throw notInPlayground
    }

    func listPendingPermissions(directory _: String?) async throws -> [PermissionRequest] { state.permissions }
    func listPendingQuestions(directory _: String?) async throws -> [QuestionRequest] { state.questions }
    func listSessions(directory _: String?) async throws -> [Session] { state.sessions }

    func createSession(parentID _: String?, title _: String?, directory _: String?) async throws -> Session {
        try unavailable("New sessions")
    }

    func getAllSessionStatuses(directory _: String?) async throws -> [SessionStatus] {
        state.activity.map { id, activity in
            if case .retrying = activity { return SessionStatus(sessionID: id, raw: ["type": "retry"]) }
            return SessionStatus(sessionID: id, raw: ["type": "busy"])
        }
    }

    func getSession(id: String, directory _: String?) async throws -> Session {
        guard let session = state.session(id) else { throw notInPlayground }
        return session
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        if let title {
            world.update { current in
                var copy = current
                copy.sessions = copy.sessions.map { session in
                    var renamed = session
                    if session.id == id { renamed.title = title }
                    return renamed
                }
                return copy
            }
        }
        return try await getSession(id: id, directory: directory)
    }

    func archiveSession(id _: String, directory _: String?) async throws -> Session { try unavailable("Archive") }

    func deleteSession(id _: String, directory _: String?) async throws -> Bool {
        world.notice("Delete")
        return false
    }

    func listChildSessions(id: String) async throws -> [Session] { state.sessions.filter { $0.parentID == id } }

    func listSessionTodos(id: String, directory _: String?) async throws -> [SessionTodo] {
        id == state.rootSessionID ? state.todos : []
    }

    func initializeSessionAgents(id _: String, messageID _: String, providerID _: String, modelID _: String) async throws -> Bool {
        world.notice("/init")
        return false
    }

    func forkSession(id _: String, atMessageID _: String?) async throws -> Session { try unavailable("Fork") }

    func abortSession(id: String, directory _: String?) async throws -> Bool {
        world.update { $0.aborted(id) }
        return true
    }

    func shareSession(id _: String) async throws -> Session { try unavailable("Share") }
    func unshareSession(id _: String) async throws -> Session { try unavailable("Share") }

    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] { state.turnDiff }

    func getVcsInfo(directory _: String?) async throws -> VcsInfo {
        VcsInfo(currentBranch: state.branch, defaultBranch: state.defaultBranch)
    }

    func getVcsStatus(directory _: String?) async throws -> [VcsFileStatus] { state.changedFiles }

    func getVcsDiff(mode _: VcsDiffMode, directory _: String?, context _: Int) async throws -> [FileDiff] { state.turnDiff }

    func summarizeSession(id _: String, providerID _: String, modelID _: String) async throws -> Bool {
        world.notice("Compact")
        return false
    }

    func revertSession(id _: String, messageID _: String, partID _: String?) async throws -> Bool {
        world.notice("Undo")
        return false
    }

    func unrevertSession(id _: String) async throws -> Bool {
        world.notice("Redo")
        return false
    }

    func commitRevert(id _: String) async throws -> Bool { true }

    func respondToPermission(sessionID _: String, permissionID: String, decision _: PermissionDecision, directory _: String?) async throws -> Bool {
        world.update { current in
            var copy = current
            copy.permissions.removeAll { $0.id == permissionID }
            return copy
        }
        return true
    }

    func replyToQuestion(questionID: String, answers _: [[String]], directory _: String?) async throws -> Bool {
        dropQuestion(questionID)
    }

    func replyToQuestion(questionID: String, answers _: [[String]], directory _: String?, sessionID _: String?) async throws -> Bool {
        dropQuestion(questionID)
    }

    func replyToQuestion(
        questionID: String,
        answers _: [[String]],
        directory _: String?,
        sessionID _: String?,
        questions _: [QuestionInfo]
    ) async throws -> Bool {
        dropQuestion(questionID)
    }

    func rejectQuestion(questionID: String, directory _: String?) async throws -> Bool { dropQuestion(questionID) }

    func rejectQuestion(questionID: String, directory _: String?, sessionID _: String?) async throws -> Bool {
        dropQuestion(questionID)
    }

    func cancelPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        world.update { $0.removingMessage(sessionID, inboxID) }
        return true
    }

    func steerPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        setDelivery(sessionID, inboxID, .steer)
    }

    func queuePendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        setDelivery(sessionID, inboxID, .queue)
    }

    func markSessionViewed(sessionId _: String, idleAtEpochMs _: Int64) async throws {}

    func moveSession(id _: String, directory _: String) async throws {
        world.notice("Move to worktree")
        throw notInPlayground
    }

    func askSideQuestion(sessionID _: String, question _: String) async throws -> String {
        try await Task.sleep(for: .milliseconds(1_200))
        return state.sideAnswer
    }

    private func dropQuestion(_ id: String) -> Bool {
        world.update { current in
            var copy = current
            copy.questions.removeAll { $0.id == id }
            return copy
        }
        return true
    }

    private func setDelivery(_ sessionID: String, _ inboxID: String, _ delivery: FollowUpDelivery) -> Bool {
        world.update { $0.mapMessages(sessionID) { $0.id == inboxID ? $0.replacing(followUpDelivery: .some(delivery)) : $0 } }
        return true
    }
}

final class PlaygroundSessionStatusStore: SessionStatusLiveStore {
    private let world: PlaygroundWorld

    init(world: PlaygroundWorld) {
        self.world = world
    }

    func observeActivity(directory _: String) -> AsyncStream<SessionActivitySnapshot> {
        let source = world.stream { PlaygroundActivityKey(ids: $0.sessions.map(\.id), activity: $0.activity) }
        let now = world.now
        return AsyncStream { continuation in
            let task = Task { @MainActor in
                for await key in source {
                    let observedAt = now()
                    continuation.yield(SessionActivitySnapshot(
                        entries: Dictionary(uniqueKeysWithValues: key.ids.map { id in
                            (id, SessionActivityEntry(activity: key.activity[id] ?? .idle, source: .server, observedAtMs: observedAt))
                        }),
                        seeded: true
                    ))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func clearCache() {}
    func invalidate(directory _: String?) async {}
    func setOptimistic(directory _: String, sessionID _: String, activity _: SessionActivity, ttlMs _: Int64) {}
}

private struct PlaygroundActivityKey: Equatable {
    let ids: [String]
    let activity: [String: SessionActivity]
}

final class PlaygroundPermissionStore: PermissionLiveStore {
    private let world: PlaygroundWorld
    init(world: PlaygroundWorld) { self.world = world }
    func observePermissions(directory _: String) -> AsyncStream<[PermissionRequest]> { world.stream(\.permissions) }
    func clearCache() {}
    func invalidate(directory _: String?) {}
}

final class PlaygroundQuestionStore: QuestionLiveStore {
    private let world: PlaygroundWorld
    init(world: PlaygroundWorld) { self.world = world }
    func observeQuestions(directory _: String) -> AsyncStream<[QuestionRequest]> { world.stream(\.questions) }
    func clearCache() {}
    func invalidate(directory _: String?) {}
}

final class PlaygroundTodoStore: SessionTodoLiveStore {
    private let world: PlaygroundWorld
    init(world: PlaygroundWorld) { self.world = world }

    func observeTodos(sessionID: String, directory _: String?) -> AsyncStream<[SessionTodo]> {
        world.stream { sessionID == $0.rootSessionID ? $0.todos : [] }
    }

    func setLive(_: Bool, sessionID _: String, directory _: String?) {}
    func clearCache() {}
    func invalidate(sessionID _: String, directory _: String?) {}
}

/// Always connected; carries no events, since the world feeds the stores directly.
final class PlaygroundEventStream: ForegroundEventStream, @unchecked Sendable {
    nonisolated var connectionState: EventStreamConnectionState { .connected }

    nonisolated func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        AsyncStream { $0.yield(.connected) }
    }

    nonisolated func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    nonisolated func start() {}
    nonisolated func stop() async {}
}

final class PlaygroundModelRepository: ModelRepository {
    func listModelOptions(directory _: String?) async throws -> ModelSelectionCatalog {
        let coder = ModelSelectionOption(
            providerID: PlaygroundContent.providerID,
            providerName: "Acme",
            modelID: PlaygroundContent.modelID,
            modelName: "Acme Coder 1",
            variants: ["low", "high"],
            contextLimitTokens: 200_000
        )
        let mini = ModelSelectionOption(
            providerID: PlaygroundContent.providerID,
            providerName: "Acme",
            modelID: "acme-mini",
            modelName: "Acme Mini",
            variants: [],
            contextLimitTokens: 64_000
        )
        return ModelSelectionCatalog(options: [coder, mini], serverDefault: coder)
    }
}

final class PlaygroundAgentRepository: AgentRepository {
    func listSelectableAgents(directory _: String?) async throws -> [SelectableAgent] { SelectableAgent.fallback }

    func listMentionableAgents(directory _: String?) async throws -> [MentionableAgent] {
        [
            MentionableAgent(name: "explore", description: "Read-only explorer"),
            MentionableAgent(name: "general", description: "General helper"),
        ]
    }
}

final class PlaygroundCommandRepository: CommandRepository {
    private let world: PlaygroundWorld
    init(world: PlaygroundWorld) { self.world = world }

    func listCommands(directory _: String?) async throws -> [ServerCommand] {
        [
            ServerCommand(name: "review", description: "Review the current changes"),
            ServerCommand(name: "test", description: "Run the test suite"),
        ]
    }

    func runCommand(sessionID: String, options: CommandRunOptions, directory _: String?) async throws {
        let text = "/\(options.command) \(options.arguments)".trimmingCharacters(in: .whitespaces)
        world.acceptPrompt(sessionID: sessionID, text: text, messageID: nil)
    }
}

/// Reports v2 with side questions, so every pill the playground shows has a server that could make it.
nonisolated struct PlaygroundConnectionProbing: ConnectionProbing {
    func probe(connection _: ServerConnection) async throws -> ServerProtocol { .v2 }

    func probeV2Features(connection _: ServerConnection) async throws -> V2ServerFeatures {
        V2ServerFeatures(sessionArchive: true, sessionTurnDiff: true, sessionGenerate: true)
    }
}

final class PlaygroundProfileStore: ConnectionProfileStore {
    private var profile: ConnectionProfile?

    func configurationRevisions() -> AsyncStream<Void> { AsyncStream { _ in } }
    func activeProfile() async -> ConnectionProfile? { profile }
    func listProfiles() async -> [ConnectionProfile] { profile.map { [$0] } ?? [] }
    func saveProfile(_ profile: ConnectionProfile) async throws { self.profile = profile }
    func applyProfile(id _: String) async {}
    func removeProfile(id _: String) async {}
    func addedWorktrees(serverBaseURL _: String) async -> [String] { [] }
    func addWorktree(serverBaseURL _: String, worktree _: String) async {}
    func removeWorktree(serverBaseURL _: String, worktree _: String) async {}
    func setAddedWorktrees(serverBaseURL _: String, worktrees _: [String]) async {}
    func clearAll() async { profile = nil }
}

final class PlaygroundDraftStore: ComposerDraftStore {
    private var drafts: [String: ComposerDraft] = [:]

    func load(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, sessionID: String) -> ComposerDraft? {
        drafts["\(worktree)|\(sessionID)"]
    }

    func save(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, sessionID: String, draft: ComposerDraft) {
        drafts["\(worktree)|\(sessionID)"] = draft
    }

    func delete(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, sessionID: String) {
        drafts["\(worktree)|\(sessionID)"] = nil
    }

    func deleteSessionEntries(connectionIdentity _: ModelSelectionConnectionIdentity, sessionID: String) {
        drafts = drafts.filter { !$0.key.hasSuffix("|\(sessionID)") }
    }

    func clearConnection(connectionIdentity _: ModelSelectionConnectionIdentity) { drafts.removeAll() }
    func clearAll() { drafts.removeAll() }
}

final class PlaygroundModelSelectionStore: SessionModelSelectionStore {
    private var selections: [String: SessionModelSelection] = [:]

    func loadSession(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, sessionID: String) async -> SessionModelSelection? {
        selections["\(worktree)|\(sessionID)"]
    }

    func saveSession(
        connectionIdentity _: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        selections["\(worktree)|\(sessionID)"] = selection
    }

    func deleteSession(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, sessionID: String) async {
        selections["\(worktree)|\(sessionID)"] = nil
    }

    func deleteSessionEntries(connectionIdentity _: ModelSelectionConnectionIdentity, sessionID: String) async {
        selections = selections.filter { !$0.key.hasSuffix("|\(sessionID)") }
    }

    func loadNewSessionPreference(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String) async -> SessionModelSelection? {
        selections[worktree]
    }

    func saveNewSessionPreference(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, selection: SessionModelSelection) async {
        selections[worktree] = selection
    }

    func clearAll() async { selections.removeAll() }
    func clearConnection(connectionIdentity _: ModelSelectionConnectionIdentity) async { selections.removeAll() }

    func peekSession(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String, sessionID: String) -> SessionModelSelection? {
        selections["\(worktree)|\(sessionID)"]
    }

    func peekNewSessionPreference(connectionIdentity _: ModelSelectionConnectionIdentity, worktree: String) -> SessionModelSelection? {
        selections[worktree]
    }
}
