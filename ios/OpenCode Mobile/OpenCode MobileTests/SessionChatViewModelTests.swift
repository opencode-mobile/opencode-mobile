import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct SessionChatViewModelTests {
    @Test func orderedPermissionRequests_prioritizesRootAndIncludesNewDescendants() {
        let rootRequest = permissionRequest(id: "root-request", sessionID: "root")
        let childRequest = permissionRequest(id: "child-request", sessionID: "child")
        let newChildRequest = permissionRequest(id: "new-child-request", sessionID: "new-child")

        let ordered = SessionChatViewModel.orderedPermissionRequests(
            rootSessionID: "root",
            sessions: [
                Session(id: "child", title: "Child", slug: nil, projectID: nil, directory: nil, parentID: "root", updatedAtEpochMs: nil),
                Session(id: "new-child", title: "New child", slug: nil, projectID: nil, directory: nil, parentID: "root", updatedAtEpochMs: nil),
            ],
            requests: [childRequest, newChildRequest, rootRequest]
        )

        #expect(ordered.map(\.id) == ["root-request", "child-request", "new-child-request"])
    }

    @Test func stalePermissionResponse_refreshesAndUsesWorktree() async {
        let request = permissionRequest(id: "permission-1", sessionID: "ses-1")
        let sessionRepository = FakeChatSessionRepository(
            sessions: [
                Session(id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: nil)
            ],
            pendingPermissions: [],
            permissionResponseError: .http(statusCode: 404, message: "Missing")
        )
        let viewModel = makeViewModel(
            sessionRepository: sessionRepository,
            permissionLiveStore: FakePermissionLiveStore(requests: [request])
        )
        defer { viewModel.stopObserving() }

        await waitUntil { viewModel.uiState.permissionRequest?.id == "permission-1" }
        await viewModel.allowPermissionOnce(requestID: request.id, sessionID: request.sessionID)
        await waitUntil { viewModel.uiState.permissionRequest == nil }

        #expect(sessionRepository.responseDirectory == "/repo")
        #expect(sessionRepository.responsePermissionID == "permission-1")
        #expect(sessionRepository.responseSessionID == "ses-1")
        #expect(sessionRepository.pendingPermissionLoads == 1)
    }

    @Test func latePermissionDecision_usesRenderedRequestIdentity() async {
        let first = permissionRequest(id: "permission-1", sessionID: "ses-1")
        let second = permissionRequest(id: "permission-2", sessionID: "child")
        let sessionRepository = FakeChatSessionRepository(
            sessions: [
                Session(id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: nil),
                Session(id: "child", title: "Child", slug: nil, projectID: nil, directory: "/repo", parentID: "ses-1", updatedAtEpochMs: nil),
            ]
        )
        let viewModel = makeViewModel(
            sessionRepository: sessionRepository,
            permissionLiveStore: FakePermissionLiveStore(requests: [first, second])
        )
        defer { viewModel.stopObserving() }

        await waitUntil { viewModel.uiState.permissionQueueCount == 2 }
        await viewModel.allowPermissionOnce(requestID: second.id, sessionID: second.sessionID)

        #expect(sessionRepository.responsePermissionID == "permission-2")
        #expect(sessionRepository.responseSessionID == "child")
    }

    @Test func permissionTopologyFailure_isReported() async {
        let sessionRepository = FakeChatSessionRepository(
            listSessionsError: .transport("offline")
        )
        let viewModel = makeViewModel(
            sessionRepository: sessionRepository,
            permissionLiveStore: FakePermissionLiveStore(
                requests: [permissionRequest(id: "permission-1", sessionID: "child")]
            )
        )
        defer { viewModel.stopObserving() }

        await waitUntil { viewModel.uiState.errorMessage == "Unable to refresh permission session tree" }

        #expect(viewModel.uiState.permissionRequest == nil)
    }

    @Test func loadMessages_success() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository())
        await viewModel.loadMessages()
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(messages.count == 1)
        #expect(messages[0].text == "Hello")
    }

    @Test func completedLatestTurnLoadsReviewChangesSummaryWithWorktree() async {
        let sessionRepository = FakeChatSessionRepository(
            sessionDiffs: [
                FileDiff(path: "one.swift", patch: nil, additions: 1, deletions: 2, status: .modified),
                FileDiff(path: "two.swift", patch: nil, additions: 3, deletions: 0, status: .added),
            ]
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(id: "user-1", role: .user, text: "Change it", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(id: "assistant-1", role: .assistant, text: "Done", model: nil, mode: nil, sentAtEpochMs: 2),
            ]),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.reviewChangesSummary != nil }

        #expect(viewModel.uiState.reviewChangesSummary == ReviewChangesSummary(fileCount: 2, additions: 4, deletions: 2))
        #expect(sessionRepository.diffRequest == .init(sessionID: "ses-1", messageID: "user-1", directory: "/repo"))
    }

    @Test func newerLatestTurnCancelsStaleReviewChangesSummary() async {
        let sessionRepository = FakeChatSessionRepository(
            sessionDiffProvider: { messageID in
                if messageID == "user-1" {
                    try await Task.sleep(for: .seconds(1))
                    return [FileDiff(path: "stale.swift", patch: nil, additions: 99, deletions: 99, status: .modified)]
                }
                return [FileDiff(path: "latest.swift", patch: nil, additions: 5, deletions: 3, status: .modified)]
            }
        )
        let region = SessionChatReviewChanges(
            sessionID: "ses-1",
            worktree: "/repo",
            sessionRepository: sessionRepository
        )

        region.updateCandidate(
            messages: [reviewChangesRow(id: "user-1")],
            hasResolvedSessionStatus: true,
            isTurnActive: false,
            isSending: false
        )
        await Task.yield()
        region.updateCandidate(
            messages: [reviewChangesRow(id: "user-1"), reviewChangesRow(id: "user-2")],
            hasResolvedSessionStatus: true,
            isTurnActive: false,
            isSending: false
        )
        await waitUntil { region.state.messageID == "user-2" }

        #expect(region.state.summary == ReviewChangesSummary(fileCount: 1, additions: 5, deletions: 3))
    }

    @Test func failedReviewChangesCountStaysHidden() async {
        let sessionRepository = FakeChatSessionRepository(sessionDiffError: .transport("offline"))
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(id: "user-1", role: .user, text: "Change it", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(id: "assistant-1", role: .assistant, text: "Done", model: nil, mode: nil, sentAtEpochMs: 2),
            ]),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        await waitUntil { sessionRepository.diffRequest != nil }

        #expect(viewModel.uiState.reviewChangesSummary == nil)
    }

    @Test func activeLatestTurnDoesNotLoadReviewChangesCount() async {
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(id: "user-1", role: .user, text: "Change it", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(id: "tool-1", role: .tool, text: "Still working", model: nil, mode: nil, sentAtEpochMs: 2),
            ]),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        try? await Task.sleep(for: .milliseconds(50))

        #expect(sessionRepository.diffRequest == nil)
        #expect(viewModel.uiState.reviewChangesSummary == nil)
    }

    @Test func benchmark_loadMessages_longMarkdownHistory() async {
        let messages = longMarkdownHistory()
        let detectorMs = milliseconds {
            _ = messages.filter { OpenCodeRenderedMarkdownDetector.shouldRender($0.text) }.count
        }
        let parserMs = await milliseconds {
            await Task.detached(priority: .userInitiated) {
                for message in messages {
                    guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else { continue }
                    _ = OpenCodeRenderedMarkdownParser.parse(message.text, isStreaming: false)
                }
            }.value
        }
        let mapper = MessageContentPreparer()
        let preparerMs = await milliseconds {
            _ = await mapper.prepareMessages(messages)
        }
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages)
        )
        defer { viewModel.stopObserving() }

        let clock = ContinuousClock()
        let elapsed = await clock.measure {
            await viewModel.loadMessages()
        }

        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected a ready benchmark timeline")
            return
        }
        let loadedMessages = viewModel.uiState.messages
        #expect(loadedMessages.count == 20)
        let line =
            "MARKDOWN_BENCHMARK ios detector-ms=\(detectorMs) parser-bridge-ms=\(parserMs) " +
            "preparer-ms=\(preparerMs) viewmodel-load-ms=\(elapsed.components.attoseconds / 1_000_000_000_000_000) " +
            "messages=20 code-lines=3000\n"
        print(String(line.dropLast()))
        fputs(line, stderr)
    }

    private func reviewChangesRow(id: String) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: .user,
            text: "Change it",
            metadataLabel: "Build",
            isPending: false,
            isStreaming: false,
            renderedMarkdown: nil,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )
    }

    @Test func loadMessages_empty() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(messages: []))
        await viewModel.loadMessages()
        guard viewModel.uiState.timeline == .empty else {
            Issue.record("Expected empty timeline")
            return
        }
    }

    @Test func loadMessages_error() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(shouldFailLoad: true))
        await viewModel.loadMessages()
        guard viewModel.uiState.timeline == .error else {
            Issue.record("Expected error timeline")
            return
        }
    }

    @Test func sendPrompt_success() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.updateComposerText("New prompt")
        await viewModel.sendPrompt()
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(viewModel.uiState.isSending == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(repository.sentOptions?.model?.providerID == "anthropic")
        #expect(repository.sentOptions?.model?.modelID == "claude-sonnet")
        #expect(repository.sentOptions?.variant == nil)
        #expect(repository.sentOptions?.mode == "build")
        #expect(messages.contains { $0.text == "Hello" })
    }

    @Test func sendPrompt_planMode_sendsPlanAgent() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.selectMode("Plan")
        viewModel.updateComposerText("Plan me")
        await viewModel.sendPrompt()
        #expect(repository.sentOptions?.mode == "plan")
    }

    @Test func loadMessages_restoresCachedModelSelectionOverHistory() async {
        let defaultOption = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: ["low", "high"],
            contextLimitTokens: 128_000
        )
        let cachedOption = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "gpt-4",
            modelName: "GPT-4",
            variants: [],
            contextLimitTokens: 128_000
        )
        let selectionStore = InMemorySessionModelSelectionStore()
        await selectionStore.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil)
        )
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
            ),
            sessionModelSelectionStore: selectionStore,
            modelRepository: FakeModelRepository(
                catalog: ModelSelectionCatalog(
                    options: [defaultOption, cachedOption],
                    serverDefault: defaultOption
                )
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.uiState.composer.selectedModel?.option.providerID == "openai")
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "gpt-4")
    }

    @Test func loadMessages_newerUserHistoryOverridesOlderCachedSelection() async {
        let historyOption = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: ["high"],
            contextLimitTokens: 128_000
        )
        let cachedOption = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "gpt-4",
            modelName: "GPT-4",
            variants: [],
            contextLimitTokens: 128_000
        )
        let selectionStore = InMemorySessionModelSelectionStore()
        await selectionStore.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(
                providerID: "openai",
                modelID: "gpt-4",
                variant: nil,
                savedAtEpochMs: 1_000
            )
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-2",
                        role: .user,
                        text: "Use Claude",
                        model: nil,
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        variant: "high",
                        mode: nil,
                        sentAtEpochMs: 2_000
                    ),
                ]
            ),
            sessionModelSelectionStore: selectionStore,
            modelRepository: FakeModelRepository(
                catalog: ModelSelectionCatalog(
                    options: [historyOption, cachedOption],
                    serverDefault: cachedOption
                )
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.uiState.composer.selectedModel?.option.providerID == "anthropic")
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "claude-sonnet")
        #expect(viewModel.uiState.composer.selectedVariant == "high")
    }

    @Test func initialization_waitsForLateCacheAndPublishesSelectionOnceReady() async {
        let defaultOption = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: [],
            contextLimitTokens: 128_000
        )
        let cachedOption = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "gpt-4",
            modelName: "GPT-4",
            variants: [],
            contextLimitTokens: 128_000
        )
        let store = InMemorySessionModelSelectionStore(blockLoads: true)
        await store.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(
                providerID: "openai",
                modelID: "gpt-4",
                variant: nil,
                savedAtEpochMs: 2_000
            )
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: []),
            sessionModelSelectionStore: store,
            modelRepository: FakeModelRepository(
                catalog: ModelSelectionCatalog(options: [defaultOption, cachedOption], serverDefault: defaultOption)
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.uiState.composer.isModelSelectionReady == false)
        #expect(viewModel.uiState.composer.selectedModel == nil)

        store.releaseLoads()
        _ = await waitUntil { viewModel.uiState.composer.isModelSelectionReady }

        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "gpt-4")
        viewModel.startObserving()
        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "gpt-4")
        viewModel.stopObserving()
    }

    @Test func selectModel_persistsAndRestoresOnNewViewModel() async {
        let defaultOption = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: ["low", "high"],
            contextLimitTokens: 128_000
        )
        let pickedOption = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "gpt-4",
            modelName: "GPT-4",
            variants: [],
            contextLimitTokens: 128_000
        )
        let catalog = ModelSelectionCatalog(
            options: [defaultOption, pickedOption],
            serverDefault: defaultOption
        )
        let selectionStore = InMemorySessionModelSelectionStore()
        let firstViewModel = makeViewModel(
            sessionModelSelectionStore: selectionStore,
            modelRepository: FakeModelRepository(catalog: catalog)
        )
        await firstViewModel.loadMessages()
        firstViewModel.selectModel(pickedOption)
        _ = await waitUntil { selectionStore.hasSavedSelection(sessionID: "ses-1") }
        firstViewModel.stopObserving()

        let secondViewModel = makeViewModel(
            sessionModelSelectionStore: selectionStore,
            modelRepository: FakeModelRepository(catalog: catalog)
        )
        await secondViewModel.loadMessages()

        #expect(secondViewModel.uiState.composer.selectedModel?.option.providerID == "openai")
        #expect(secondViewModel.uiState.composer.selectedModel?.option.modelID == "gpt-4")
        #expect(await selectionStore.loadNewSessionPreference(
            connectionIdentity: ModelSelectionConnectionIdentity(
                serverBaseURL: "http://127.0.0.1:4096/",
                username: "opencode"
            ),
            worktree: "/repo"
        )?.modelID == "gpt-4")
    }

    @Test func loadMessages_showsNoticeWhenCachedModelUnavailable() async {
        let defaultOption = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: ["low", "high"],
            contextLimitTokens: 128_000
        )
        let selectionStore = InMemorySessionModelSelectionStore()
        await selectionStore.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "removed", modelID: "missing-model", variant: nil)
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .user,
                        text: "Hello",
                        model: "claude-sonnet",
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        mode: nil,
                        sentAtEpochMs: 1_000
                    ),
                ]
            ),
            sessionModelSelectionStore: selectionStore,
            modelRepository: FakeModelRepository(
                catalog: ModelSelectionCatalog(options: [defaultOption], serverDefault: defaultOption)
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.uiState.composer.selectedModel?.option.providerID == "anthropic")
        #expect(viewModel.uiState.composer.modelNotice == "Selected model is unavailable. Using Claude Sonnet.")
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

        #expect(viewModel.uiState.composer.selectedModel?.option.providerID == "anthropic")
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "claude-sonnet")
        #expect(viewModel.uiState.composer.selectedVariant == "high")
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
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(messages.map(\.text) == ["Earlier", "Later"])
    }

    @Test func loadMessages_preservesOrderWhenMessageTimestampIsMissing() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(id: "msg-1", role: .assistant, text: "Earlier", model: nil, mode: nil, sentAtEpochMs: 1_000),
                    Message(id: "msg-2", role: .user, text: "Hydrating", model: nil, mode: nil, sentAtEpochMs: nil),
                    Message(id: "msg-3", role: .assistant, text: "Later", model: nil, mode: nil, sentAtEpochMs: 3_000),
                ]
            )
        )
        await viewModel.loadMessages()
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(messages.map(\.text) == ["Earlier", "Hydrating", "Later"])
    }

    @Test func sendPrompt_failure() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(shouldFailSend: true))
        await viewModel.loadMessages()
        viewModel.updateComposerText("Fail me")
        await viewModel.sendPrompt()
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline with error message")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(viewModel.uiState.isSending == false)
        #expect(!messages.contains { $0.text == "Fail me" })
        #expect(viewModel.uiState.errorMessage != nil)
    }

    @Test func sendPrompt_postSendReloadFailure_keepsOptimisticMessageWithoutError() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.updateComposerText("Pending prompt")
        await viewModel.sendPrompt()
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.isSending == false)
        #expect(messages.contains { $0.text == "Pending prompt" && $0.isPending })
        #expect(viewModel.uiState.errorMessage == nil)
    }

    @Test func quietRefreshDuringSend_keepsPendingMessageAndSendingFlag() async {
        let repository = FakeMessageRepository(holdSend: true)
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: repository, eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.updateComposerText("New prompt")
        let sendTask = Task { await viewModel.sendPrompt() }
        try? await Task.sleep(for: .milliseconds(50))
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline while send is in flight")
            repository.releaseSend()
            sendTask.cancel()
            return
        }
        let messages = viewModel.uiState.messages
        #expect(viewModel.uiState.isSending)
        #expect(messages.contains { $0.text == "New prompt" && $0.isPending })

        repository.messages = [Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil)]
        eventStream.emit(OpencodeEvent(type: "message.updated", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))

        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline after quiet refresh")
            repository.releaseSend()
            _ = await sendTask.value
            return
        }
        let refreshed = viewModel.uiState.messages
        #expect(viewModel.uiState.isSending)
        #expect(refreshed.contains { $0.text == "New prompt" && $0.isPending })

        repository.releaseSend()
        await sendTask.value
    }

    @Test func startObserving_afterStop_resumesEventReloads() async {
        let repository = FakeObservingMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.stopObserving()
        viewModel.startObserving()
        try? await Task.sleep(for: .milliseconds(200))
        let updated = [
            Message(id: "msg-2", role: .assistant, text: "Resumed", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        repository.emit(updated)
        let resumed = await waitUntil(timeoutMs: 2_000) {
            viewModel.uiState.timeline == .ready &&
                viewModel.uiState.messages.contains { $0.text == "Resumed" }
        }
        #expect(resumed)
    }

    @Test func stopObserving_cancelsBackgroundTasks() async {
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.stopObserving()
        eventStream.emit(OpencodeEvent(type: "message.updated", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        let messages = viewModel.uiState.messages
        #expect(messages.count == 1)
        #expect(messages[0].text == "Hello")
    }

    @Test func messageUpdatedEvent_reloadsMessages() async {
        let repository = FakeObservingMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        try? await Task.sleep(for: .milliseconds(50))
        let updated = [
            Message(id: "msg-2", role: .assistant, text: "Updated", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        repository.emit(updated)
        try? await Task.sleep(for: .milliseconds(500))
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        #expect(viewModel.uiState.messages.contains { $0.text == "Updated" })
    }

    @Test func connectionState_mapsToSessionConnectionStatus() {
        #expect(SessionConnectionStatus(connectionState: .connected) == .live)
        #expect(SessionConnectionStatus(connectionState: .connecting) == .connecting)
        #expect(SessionConnectionStatus(connectionState: .reconnecting) == .connecting)
        #expect(SessionConnectionStatus(connectionState: .idle) == .offline)
        #expect(SessionConnectionStatus(connectionState: .disconnected) == .offline)
    }

    // MARK: - isStreaming propagation

    @Test func rowFromMessage_streamingTextPart_setsIsStreaming() {
        let message = Message(
            id: "msg-1",
            role: .assistant,
            text: "Hello",
            parts: [.text(TextPart(text: "Hello", isStreaming: true))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let viewModel = makeViewModel()
        let row = viewModel.row(from: message)
        #expect(row?.isStreaming == true)
    }

    @Test func rowFromMessage_nonStreamingTextPart_setsIsStreamingFalse() {
        let message = Message(
            id: "msg-1",
            role: .assistant,
            text: "Done",
            parts: [.text(TextPart(text: "Done", isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let viewModel = makeViewModel()
        let row = viewModel.row(from: message)
        #expect(row?.isStreaming == false)
    }

    @Test func rowFromMessage_toolPartOnly_isNotStreaming() {
        let message = Message(
            id: "msg-1",
            role: .assistant,
            text: "",
            parts: [.tool(ToolPart(toolName: "bash", status: .completed))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let viewModel = makeViewModel()
        let row = viewModel.row(from: message)
        #expect(row?.isStreaming == false)
        guard case .toolCard(let card) = row?.blocks.first else {
            Issue.record("Expected tool card block")
            return
        }
        #expect(card.title == "Shell")
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

        #expect(viewModel.uiState.contextUsageFraction == 0.25)
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

        #expect(viewModel.uiState.contextUsageFraction == 1)
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
        #expect(noTokens.uiState.contextUsageFraction == nil)

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
        #expect(noLimit.uiState.contextUsageFraction == nil)
    }

    private func makeViewModel(
        messageRepository: (any MessageRepository)? = nil,
        eventStream: FakeForegroundEventStream? = nil,
        sessionModelSelectionStore: (any SessionModelSelectionStore)? = nil,
        modelRepository: FakeModelRepository = FakeModelRepository(),
        sessionRepository: FakeChatSessionRepository? = nil,
        permissionLiveStore: (any PermissionLiveStore)? = nil
    ) -> SessionChatViewModel {
        let eventStream = eventStream ?? FakeForegroundEventStream()
        let sessionRepository = sessionRepository ?? FakeChatSessionRepository()
        let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
            sessionRepository: sessionRepository,
            eventStream: eventStream
        )
        let fake = messageRepository ?? FakeMessageRepository()
        let repo: any MessageRepository =
            if fake is FakeObservingMessageRepository {
                fake
            } else if fake is SyncingTestMessageRepository {
                fake
            } else if let fakeRepository = fake as? FakeMessageRepository {
                SyncingTestMessageRepository(fake: fakeRepository, eventStream: eventStream)
            } else {
                fake
            }
        let selectionStore = sessionModelSelectionStore ?? InMemorySessionModelSelectionStore()
        let activeConnectionManager = ActiveConnectionManager()
        activeConnectionManager.apply(
            ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
        )
        let viewModel = SessionChatViewModel(
            sessionID: "ses-1",
            sessionTitle: "Title",
            worktree: "/repo",
            messageRepository: repo,
            modelRepository: modelRepository,
            sessionRepository: sessionRepository,
            permissionLiveStore: permissionLiveStore,
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: eventStream,
            sessionModelSelectionStore: selectionStore,
            activeConnectionManager: activeConnectionManager
        )
        viewModel.startObserving()
        return viewModel
    }

    private func longMarkdownHistory() -> [Message] {
        (1...20).map { messageIndex in
            Message(
                id: "benchmark-\(messageIndex)",
                role: .assistant,
                text: "## Response \(messageIndex)\n\n```typescript\n" +
                    (0..<150).map { lineIndex in
                        "const item\(lineIndex) = response\(lineIndex)?.value ?? 'markdown benchmark';"
                    }.joined(separator: "\n") +
                    "\n```\n",
                model: nil,
                mode: nil,
                sentAtEpochMs: Int64(messageIndex)
            )
        }
    }

    private func milliseconds(_ block: () -> Void) -> Int64 {
        let clock = ContinuousClock()
        let elapsed = clock.measure(block)
        return elapsed.components.attoseconds / 1_000_000_000_000_000
    }

    private func milliseconds(_ block: () async -> Void) async -> Int64 {
        let clock = ContinuousClock()
        let elapsed = await clock.measure(block)
        return elapsed.components.attoseconds / 1_000_000_000_000_000
    }

    private func waitUntil(
        timeoutMs: Int = 2_000,
        predicate: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }
}

@MainActor
private final class SyncingTestMessageRepository: MessageRepository, @unchecked Sendable {
    private let fake: FakeMessageRepository
    private let sync: MessageSync
    private let displayPipeline: DisplayMessagePipeline
    private let contentPreparer: MessageContentPreparer

    init(
        fake: FakeMessageRepository,
        eventStream: FakeForegroundEventStream,
        contentPreparer: MessageContentPreparer = .shared
    ) {
        self.fake = fake
        self.contentPreparer = contentPreparer
        self.sync = MessageSync(
            listMessages: { sessionID, directory in
                try await fake.listMessages(sessionID: sessionID, directory: directory)
            },
            store: InMemoryMessageStore(),
            eventStream: eventStream
        )
        self.displayPipeline = DisplayMessagePipeline(messageSync: sync, contentPreparer: contentPreparer)
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        sync.observeMessages(sessionID: sessionID, directory: directory)
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        displayPipeline.observeDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await sync.refreshMessages(sessionID: sessionID, directory: directory)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        try await displayPipeline.refreshDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await contentPreparer.prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {
        sync.clearCache()
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await fake.listMessages(sessionID: sessionID, directory: directory)
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        try await fake.getMessage(sessionID: sessionID, messageID: messageID, directory: directory)
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        try await fake.sendMessage(sessionID: sessionID, text: text, directory: directory)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        try await fake.sendPromptAsync(sessionID: sessionID, options: options, directory: directory)
    }
}

@MainActor
private final class FakeObservingMessageRepository: MessageRepository, @unchecked Sendable {
    private var publish: ((MessageListUpdate) -> Void)?
    private var observation: SharedLiveObservation<MessageListUpdate>?

    private func ensureObservation() -> SharedLiveObservation<MessageListUpdate> {
        if let observation {
            return observation
        }
        let created = SharedLiveObservation<MessageListUpdate> { yield in
            Task { @MainActor [weak self] in
                self?.publish = yield
                await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in }
            }
        }
        observation = created
        return created
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        ensureObservation().stream()
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            let task = Task {
                var previousByID: [String: DisplayMessage] = [:]
                for await update in observeMessages(sessionID: sessionID, directory: directory) {
                    switch update {
                    case .loadFailed(let error):
                        continuation.yield(.loadFailed(error))
                    case .messages(let messages):
                        let prepared = await MessageContentPreparer.shared.prepareMessages(messages, previousByID: previousByID)
                        previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                        continuation.yield(.messages(prepared))
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        throw MessageError.transport("unused")
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        throw MessageError.transport("unused")
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await MessageContentPreparer.shared.prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        [Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil)]
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        fatalError()
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        fatalError()
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}

    func emit(_ messages: [Message]) {
        publish?(.messages(messages))
    }

    func emitLoadFailed(_ error: Error) {
        publish?(.loadFailed(error))
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

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { _ in }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            Task {
                do {
                    let messages = try await listMessages(sessionID: sessionID, directory: directory)
                    let prepared = await MessageContentPreparer.shared.prepareMessages(messages)
                    continuation.yield(.messages(prepared))
                } catch {
                    continuation.yield(.loadFailed(error))
                }
                continuation.finish()
            }
        }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await listMessages(sessionID: sessionID, directory: directory)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        let messages = try await refreshMessages(sessionID: sessionID, directory: directory)
        return await MessageContentPreparer.shared.prepareMessages(messages)
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await MessageContentPreparer.shared.prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {}

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
    let catalog: ModelSelectionCatalog

    init(catalog: ModelSelectionCatalog? = nil) {
        let option = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: ["low", "high"],
            contextLimitTokens: 128_000
        )
        self.catalog = catalog ?? ModelSelectionCatalog(options: [option], serverDefault: option)
    }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        catalog
    }
}

@MainActor
private final class InMemorySessionModelSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private var saved: [String: SessionModelSelection] = [:]
    private var preferences: [String: SessionModelSelection] = [:]
    private var blockLoads: Bool

    init(blockLoads: Bool = false) {
        self.blockLoads = blockLoads
    }

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? {
        while blockLoads { await Task.yield() }
        return saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)]
    }

    func releaseLoads() {
        blockLoads = false
    }

    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {
        saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)] = selection
    }

    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection? {
        await load(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID)
    }

    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        await save(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID, selection: selection)
    }

    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        saved.removeValue(forKey: key(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        saved.keys
            .filter { $0.hasPrefix(connectionIdentity.serverBaseURL) && $0.hasSuffix("|\(sessionID)") }
            .forEach { saved.removeValue(forKey: $0) }
    }

    func hasSavedSelection(
        sessionID: String,
        serverBaseURL: String = "http://127.0.0.1:4096/",
        worktree: String = "/repo"
    ) -> Bool {
        saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)] != nil
    }

    func clearAll() async {
        saved.removeAll()
        preferences.removeAll()
    }

    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection? {
        preferences[preferenceKey(connectionIdentity, worktree)]
    }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {
        preferences[preferenceKey(connectionIdentity, worktree)] = selection
    }

    private func preferenceKey(_ identity: ModelSelectionConnectionIdentity, _ worktree: String) -> String {
        [identity.serverBaseURL, identity.username, worktree].joined(separator: "|")
    }

    private func key(serverBaseURL: String, worktree: String, sessionID: String) -> String {
        let trimmed = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.hasSuffix("/") ? trimmed : trimmed + "/"
        return [normalized, worktree, sessionID].joined(separator: "|")
    }
}

private final class FakeChatSessionRepository: SessionRepository, @unchecked Sendable {
    struct DiffRequest: Equatable {
        let sessionID: String
        let messageID: String?
        let directory: String?
    }

    private let sessions: [Session]
    private let pendingPermissions: [PermissionRequest]
    private let permissionResponseError: SessionError?
    private let listSessionsError: SessionError?
    private let sessionDiffs: [FileDiff]
    private let sessionDiffError: SessionError?
    private let sessionDiffProvider: (@Sendable (String?) async throws -> [FileDiff])?
    private let sessionStatusType: String
    private(set) var responseDirectory: String?
    private(set) var responsePermissionID: String?
    private(set) var responseSessionID: String?
    private(set) var pendingPermissionLoads = 0
    private(set) var diffRequest: DiffRequest?

    init(
        sessions: [Session] = [],
        pendingPermissions: [PermissionRequest] = [],
        permissionResponseError: SessionError? = nil,
        listSessionsError: SessionError? = nil,
        sessionDiffs: [FileDiff] = [],
        sessionDiffError: SessionError? = nil,
        sessionStatusType: String = "idle",
        sessionDiffProvider: (@Sendable (String?) async throws -> [FileDiff])? = nil
    ) {
        self.sessions = sessions
        self.pendingPermissions = pendingPermissions
        self.permissionResponseError = permissionResponseError
        self.listSessionsError = listSessionsError
        self.sessionDiffs = sessionDiffs
        self.sessionDiffError = sessionDiffError
        self.sessionStatusType = sessionStatusType
        self.sessionDiffProvider = sessionDiffProvider
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        pendingPermissionLoads += 1
        return pendingPermissions
    }

    func listSessions(directory: String?) async throws -> [Session] {
        if let listSessionsError { throw listSessionsError }
        return sessions
    }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        [SessionStatus(sessionID: "ses-1", raw: ["type": sessionStatusType])]
    }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        diffRequest = DiffRequest(sessionID: id, messageID: messageID, directory: directory)
        if let sessionDiffProvider { return try await sessionDiffProvider(messageID) }
        if let sessionDiffError { throw sessionDiffError }
        return sessionDiffs
    }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool {
        responseDirectory = directory
        responsePermissionID = permissionID
        responseSessionID = sessionID
        if let permissionResponseError { throw permissionResponseError }
        return true
    }
}

@MainActor
private final class FakePermissionLiveStore: PermissionLiveStore {
    private let requests: [PermissionRequest]

    init(requests: [PermissionRequest]) {
        self.requests = requests
    }

    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        AsyncStream { continuation in
            continuation.yield(requests)
            continuation.finish()
        }
    }

    func clearCache() {}
}

private func permissionRequest(
    id: String,
    sessionID: String
) -> PermissionRequest {
    PermissionRequest(
        id: id,
        sessionID: sessionID,
        permission: "bash",
        patterns: ["git status"],
        metadata: [:],
        always: [],
        toolMessageID: nil,
        toolCallID: nil
    )
}
