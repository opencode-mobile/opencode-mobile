import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatViewModelTests {
    @Test func loadMessages_success() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository())
        await viewModel.loadMessages()
        guard case let .loaded(_, _, messages, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(messages.count == 1)
        #expect(messages[0].text == "Hello")
    }

    @Test func loadMessages_empty() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(messages: []))
        await viewModel.loadMessages()
        guard case .empty = viewModel.state else {
            Issue.record("Expected empty state")
            return
        }
    }

    @Test func loadMessages_error() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(shouldFailLoad: true))
        await viewModel.loadMessages()
        guard case .error = viewModel.state else {
            Issue.record("Expected error state")
            return
        }
    }

    @Test func sendPrompt_success() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.updateComposerText("New prompt")
        await viewModel.sendPrompt()
        guard case let .loaded(_, _, messages, _, isSending, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(isSending == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(repository.sentOptions?.model?.providerID == "anthropic")
        #expect(repository.sentOptions?.model?.modelID == "claude-sonnet")
        #expect(repository.sentOptions?.variant == nil)
        #expect(messages.contains { $0.text == "Hello" })
    }

    @Test func loadMessages_initializesModelFromLatestMessage() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-2",
                        role: .assistant,
                        text: "Reply",
                        model: "claude-sonnet",
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        variant: "high",
                        mode: nil,
                        sentAtEpochMs: 2_000
                    ),
                ]
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.state.composer.selectedModel?.option.providerID == "anthropic")
        #expect(viewModel.state.composer.selectedModel?.option.modelID == "claude-sonnet")
        #expect(viewModel.state.composer.selectedVariant == "high")
    }

    @Test func loadMessages_sortsOldestToNewest() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(id: "msg-2", role: .user, text: "Later", model: nil, mode: nil, sentAtEpochMs: 2_000),
                    Message(id: "msg-1", role: .user, text: "Earlier", model: nil, mode: nil, sentAtEpochMs: 1_000),
                ]
            )
        )
        await viewModel.loadMessages()
        guard case let .loaded(_, _, messages, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(messages.map(\.text) == ["Earlier", "Later"])
    }

    @Test func sendPrompt_failure() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(shouldFailSend: true))
        await viewModel.loadMessages()
        viewModel.updateComposerText("Fail me")
        await viewModel.sendPrompt()
        guard case let .loaded(_, _, messages, _, isSending, _, errorBanner) = viewModel.state else {
            Issue.record("Expected loaded state with banner")
            return
        }
        #expect(isSending == false)
        #expect(!messages.contains { $0.text == "Fail me" })
        #expect(errorBanner != nil)
    }

    @Test func sendPrompt_postSendReloadFailure_keepsOptimisticMessageWithoutError() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.updateComposerText("Pending prompt")
        await viewModel.sendPrompt()
        guard case let .loaded(_, _, messages, _, isSending, _, errorBanner) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(repository.sendPromptAsyncCalled)
        #expect(isSending == false)
        #expect(messages.contains { $0.text == "Pending prompt" && $0.isPending })
        #expect(errorBanner == nil)
    }

    @Test func quietRefreshDuringSend_keepsPendingMessageAndSendingFlag() async {
        let repository = FakeMessageRepository(holdSend: true)
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: repository, eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.updateComposerText("New prompt")
        let sendTask = Task { await viewModel.sendPrompt() }
        try? await Task.sleep(for: .milliseconds(50))
        guard case let .loaded(_, _, messages, _, isSending, _, _) = viewModel.state else {
            Issue.record("Expected loaded state while send is in flight")
            repository.releaseSend()
            sendTask.cancel()
            return
        }
        #expect(isSending)
        #expect(messages.contains { $0.text == "New prompt" && $0.isPending })

        repository.messages = [Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil)]
        eventStream.emit(OpencodeEvent(type: "message.updated", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))

        guard case let .loaded(_, _, refreshed, _, stillSending, _, _) = viewModel.state else {
            Issue.record("Expected loaded state after quiet refresh")
            repository.releaseSend()
            _ = await sendTask.value
            return
        }
        #expect(stillSending)
        #expect(refreshed.contains { $0.text == "New prompt" && $0.isPending })

        repository.releaseSend()
        await sendTask.value
    }

    @Test func startObserving_afterStop_resumesEventReloads() async {
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: repository, eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.stopObserving()
        viewModel.startObserving()
        try? await Task.sleep(for: .milliseconds(50))
        repository.messages = [
            Message(id: "msg-2", role: .assistant, text: "Resumed", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        eventStream.emit(OpencodeEvent(type: "message.updated", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))
        guard case let .loaded(_, _, messages, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(messages.contains { $0.text == "Resumed" })
    }

    @Test func stopObserving_cancelsBackgroundTasks() async {
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.stopObserving()
        eventStream.emit(OpencodeEvent(type: "message.updated", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))
        guard case let .loaded(_, _, messages, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(messages.count == 1)
        #expect(messages[0].text == "Hello")
    }

    @Test func messageUpdatedEvent_reloadsMessages() async {
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: repository, eventStream: eventStream)
        await viewModel.loadMessages()
        try? await Task.sleep(for: .milliseconds(50))
        repository.messages = [
            Message(id: "msg-2", role: .assistant, text: "Updated", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        eventStream.emit(OpencodeEvent(type: "message.updated", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))
        guard case let .loaded(_, _, messages, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(messages.contains { $0.text == "Updated" })
    }

    @Test func connectionState_mapsToSessionConnectionStatus() {
        #expect(SessionConnectionStatus(connectionState: .connected) == .live)
        #expect(SessionConnectionStatus(connectionState: .connecting) == .connecting)
        #expect(SessionConnectionStatus(connectionState: .reconnecting) == .connecting)
        #expect(SessionConnectionStatus(connectionState: .idle) == .offline)
        #expect(SessionConnectionStatus(connectionState: .disconnected) == .offline)
    }

    @Test func contextUsageFraction_usesLatestAssistantTokensAndModelLimit() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(id: "msg-1", role: .assistant, text: "Earlier", model: nil, mode: nil, sentAtEpochMs: nil, contextInputTokens: 10_000),
                    Message(
                        id: "msg-2",
                        role: .assistant,
                        text: "Latest",
                        model: "claude-sonnet",
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        mode: nil,
                        sentAtEpochMs: nil,
                        contextInputTokens: 32_000
                    ),
                ]
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.contextUsageFraction == 0.25)
    }

    @Test func contextUsageFraction_clampsAboveOne() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "Too much",
                        model: "claude-sonnet",
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        mode: nil,
                        sentAtEpochMs: nil,
                        contextInputTokens: 160_000
                    ),
                ]
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.contextUsageFraction == 1)
    }

    @Test func contextUsageFraction_unavailableWhenTokensOrLimitMissing() async {
        let noTokens = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "No tokens",
                        model: "claude-sonnet",
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ]
            )
        )
        await noTokens.loadMessages()
        #expect(noTokens.contextUsageFraction == nil)

        let noLimit = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "No limit",
                        model: "unknown",
                        modelProviderID: "missing",
                        modelID: "model",
                        mode: nil,
                        sentAtEpochMs: nil,
                        contextInputTokens: 12_000
                    ),
                ]
            )
        )
        await noLimit.loadMessages()
        #expect(noLimit.contextUsageFraction == nil)
    }

    private func makeViewModel(
        messageRepository: FakeMessageRepository,
        eventStream: FakeForegroundEventStream? = nil
    ) -> SessionChatViewModel {
        let eventStream = eventStream ?? FakeForegroundEventStream()
        let sessionRepository = FakeChatSessionRepository()
        let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
            sessionRepository: sessionRepository,
            eventStream: eventStream
        )
        let messageLiveStore = DefaultMessageLiveStore(
            messageRepository: messageRepository,
            eventStream: eventStream
        )
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            worktree: "/repo",
            messageRepository: messageRepository,
            modelRepository: FakeModelRepository(),
            sessionRepository: sessionRepository,
            messageLiveStore: messageLiveStore,
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: eventStream
        )
        viewModel.startObserving()
        return viewModel
    }
}

private final class FakeMessageRepository: MessageRepository, @unchecked Sendable {
    var messages: [Message]
    let shouldFailLoad: Bool
    let shouldFailSend: Bool
    let failListMessagesAfterSend: Bool
    let holdSend: Bool
    var sendPromptAsyncCalled = false
    var sentOptions: PromptSendOptions?
    private var listMessagesCallCount = 0
    private var sendContinuation: CheckedContinuation<Void, Never>?

    init(
        messages: [Message] = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
        ],
        shouldFailLoad: Bool = false,
        shouldFailSend: Bool = false,
        failListMessagesAfterSend: Bool = false,
        holdSend: Bool = false
    ) {
        self.messages = messages
        self.shouldFailLoad = shouldFailLoad
        self.shouldFailSend = shouldFailSend
        self.failListMessagesAfterSend = failListMessagesAfterSend
        self.holdSend = holdSend
    }

    func releaseSend() {
        sendContinuation?.resume()
        sendContinuation = nil
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        listMessagesCallCount += 1
        if shouldFailLoad {
            throw MessageError.transport("load failed")
        }
        if failListMessagesAfterSend, listMessagesCallCount > 1 {
            throw MessageError.transport("reload failed")
        }
        return messages
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        fatalError()
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        fatalError()
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        sendPromptAsyncCalled = true
        sentOptions = options
        if shouldFailSend {
            throw MessageError.transport("send failed")
        }
        if holdSend {
            await withCheckedContinuation { sendContinuation = $0 }
        }
    }
}

private struct FakeModelRepository: ModelRepository {
    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        let option = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: ["low", "high"],
            contextLimitTokens: 128_000
        )
        return ModelSelectionCatalog(options: [option], serverDefault: option)
    }
}

private struct FakeChatSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] { fatalError() }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
    }
    func getSession(id: String) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool { fatalError() }
}
