import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct SessionChatViewModelTests {
    @Test func archiveSession_successLeavesSession() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.archiveSession()
        await waitUntil { viewModel.uiState.shouldLeaveSession }

        #expect(sessionRepository.archiveRequest == .init(id: "ses-1", directory: "/repo"))
        #expect(viewModel.uiState.isLifecycleActionInFlight == false)
        #expect(viewModel.uiState.errorMessage == nil)
    }

    @Test func archiveSession_failureKeepsSessionWithError() async {
        let sessionRepository = FakeChatSessionRepository(
            archiveError: .http(statusCode: 500, message: "boom")
        )
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.archiveSession()
        await waitUntil { viewModel.uiState.snackbar?.message == "Couldn't archive session." }
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)

        #expect(viewModel.uiState.shouldLeaveSession == false)
    }

    @Test func deleteSession_successLeavesSession() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.deleteSession()
        await waitUntil { viewModel.uiState.shouldLeaveSession }

        #expect(sessionRepository.deleteRequest == .init(id: "ses-1", directory: "/repo"))
    }

    @Test func deleteSession_failureKeepsSessionWithError() async {
        let sessionRepository = FakeChatSessionRepository(
            deleteError: .http(statusCode: 500, message: "boom")
        )
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.deleteSession()
        await waitUntil { viewModel.uiState.snackbar?.message == "Couldn't delete session." }
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)

        #expect(viewModel.uiState.shouldLeaveSession == false)
    }

    @Test func deleteSession_falseResultKeepsSessionWithError() async {
        let sessionRepository = FakeChatSessionRepository(deleteResult: false)
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.deleteSession()
        await waitUntil { viewModel.uiState.snackbar?.message == "Couldn't delete session." }
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)

        #expect(viewModel.uiState.shouldLeaveSession == false)
    }

    @Test func archiveSession_disablesWhileInFlight() async {
        let gate = AsyncGate()
        let sessionRepository = FakeChatSessionRepository(archiveHold: gate)
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.archiveSession()
        await waitUntil { sessionRepository.archiveCallCount == 1 && viewModel.uiState.isLifecycleActionInFlight }
        viewModel.archiveSession()
        #expect(sessionRepository.archiveCallCount == 1)

        await gate.open()
        await waitUntil { viewModel.uiState.shouldLeaveSession }
    }

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

        await waitUntil { viewModel.uiState.snackbar?.message == "Unable to refresh permission session tree" }
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)

        #expect(viewModel.uiState.permissionRequest == nil)
    }

    @Test func permissionRequest_takesPriorityOverQuestionRequest() async {
        let viewModel = makeViewModel(
            sessionRepository: FakeChatSessionRepository(
                sessions: [
                    Session(id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: nil)
                ]
            ),
            permissionLiveStore: FakePermissionLiveStore(requests: [permissionRequest(id: "permission-1", sessionID: "ses-1")]),
            questionLiveStore: FakeQuestionLiveStore(requests: [questionRequest(id: "que-1", sessionID: "ses-1")])
        )
        defer { viewModel.stopObserving() }

        await waitUntil { viewModel.uiState.permissionRequest?.id == "permission-1" }
        #expect(viewModel.uiState.questionRequest == nil)
    }

    @Test func staleQuestionReply_refreshesAndRemovesMissingRequest() async {
        let request = questionRequest(id: "que-1", sessionID: "ses-1")
        let sessionRepository = FakeChatSessionRepository(
            sessions: [
                Session(id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: nil)
            ],
            pendingQuestions: [],
            questionResponseError: .http(statusCode: 404, message: "Missing")
        )
        let viewModel = makeViewModel(
            sessionRepository: sessionRepository,
            questionLiveStore: FakeQuestionLiveStore(requests: [request])
        )
        defer { viewModel.stopObserving() }

        await waitUntil { viewModel.uiState.questionRequest?.id == "que-1" }
        viewModel.toggleQuestionOption(requestID: request.id, sessionID: request.sessionID, label: "A")
        await viewModel.submitQuestion(requestID: request.id, sessionID: request.sessionID)
        await waitUntil { viewModel.uiState.questionRequest == nil }

        #expect(sessionRepository.repliedQuestionID == "que-1")
        #expect(sessionRepository.pendingQuestionLoads == 1)
    }

    @Test func lateQuestionDismissFailure_doesNotPaintErrorOnVisibleRequest() async {
        let first = questionRequest(id: "que-1", sessionID: "ses-1")
        let second = questionRequest(id: "que-2", sessionID: "child")
        let gate = AsyncGate()
        let sessionRepository = FakeChatSessionRepository(
            sessions: [
                Session(id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: nil),
                Session(id: "child", title: "Child", slug: nil, projectID: nil, directory: "/repo", parentID: "ses-1", updatedAtEpochMs: nil)
            ],
            questionResponseError: .http(statusCode: 500, message: "boom"),
            questionHold: gate
        )
        let viewModel = makeViewModel(
            sessionRepository: sessionRepository,
            questionLiveStore: FakeQuestionLiveStore(requests: [first, second])
        )
        defer { viewModel.stopObserving() }

        await waitUntil { viewModel.uiState.questionRequest?.id == "que-1" }
        let dismissTask = Task {
            await viewModel.dismissQuestion(requestID: second.id, sessionID: second.sessionID)
        }
        await waitUntil { sessionRepository.rejectedQuestionID == "que-2" }
        #expect(viewModel.uiState.questionSubmitting == false)
        #expect(viewModel.uiState.questionRequest?.id == "que-1")

        await gate.open()
        await dismissTask.value

        #expect(viewModel.uiState.questionRequest?.id == "que-1")
        #expect(viewModel.uiState.questionErrorMessage == nil)
        #expect(viewModel.uiState.questionSubmitting == false)
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

    @Test func nonTimelinePublish_reusesTimelineFrame() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .user,
                        text: "Hello",
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: 1
                    ),
                ]
            )
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil {
            viewModel.uiState.timeline == .ready
                && !viewModel.uiState.timelineFrame.rows.isEmpty
        }

        let frameBefore = viewModel.uiState.timelineFrame
        #expect(frameBefore.frameRevision > 0)
        #expect(!frameBefore.rows.isEmpty)

        viewModel.updateComposerText("drafting without timeline remap")
        #expect(viewModel.uiState.composer.text == "drafting without timeline remap")
        #expect(viewModel.uiState.timelineFrame.frameRevision == frameBefore.frameRevision)
        #expect(viewModel.uiState.timelineFrame.rows.map(\.id) == frameBefore.rows.map(\.id))
        // Same value reuse — not a remapped copy with a new revision.
        #expect(viewModel.uiState.timelineFrame == frameBefore)
    }

    @Test func openRenameSession_prefillsCurrentTitle() async {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }

        viewModel.openRenameSession()

        #expect(viewModel.uiState.rename.isDialogVisible)
        #expect(viewModel.uiState.rename.draftTitle == "Title")
        #expect(viewModel.uiState.rename.errorMessage == nil)
        #expect(!viewModel.uiState.rename.isSubmitting)
    }

    @Test func confirmRenameSession_emptyTitle_keepsDialogWithoutApiCall() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.openRenameSession()
        viewModel.updateRenameDraft("   ")
        await viewModel.confirmRenameSession()

        #expect(sessionRepository.updateRequest == nil)
        #expect(viewModel.uiState.rename.isDialogVisible)
        #expect(viewModel.uiState.rename.errorMessage == "Enter a session title")
        #expect(viewModel.uiState.sessionTitle == "Title")
    }

    @Test func confirmRenameSession_unchangedTitle_dismissesWithoutApiCall() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.openRenameSession()
        viewModel.updateRenameDraft("  Title  ")
        await viewModel.confirmRenameSession()

        #expect(sessionRepository.updateRequest == nil)
        #expect(!viewModel.uiState.rename.isDialogVisible)
        #expect(viewModel.uiState.sessionTitle == "Title")
    }

    @Test func confirmRenameSession_success_updatesTitleAndDismisses() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.openRenameSession()
        viewModel.updateRenameDraft("Renamed title")
        await viewModel.confirmRenameSession()

        #expect(sessionRepository.updateRequest == .init(id: "ses-1", title: "Renamed title", directory: "/repo"))
        #expect(!viewModel.uiState.rename.isDialogVisible)
        #expect(viewModel.uiState.sessionTitle == "Renamed title")
        #expect(viewModel.uiState.sessionID == "ses-1")
    }

    @Test func confirmRenameSession_failure_keepsPreviousTitleAndShowsError() async {
        let sessionRepository = FakeChatSessionRepository(
            updateSessionError: .http(statusCode: 500, message: "boom")
        )
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }

        viewModel.openRenameSession()
        viewModel.updateRenameDraft("Renamed title")
        await viewModel.confirmRenameSession()

        #expect(viewModel.uiState.rename.isDialogVisible)
        #expect(!viewModel.uiState.rename.isSubmitting)
        #expect(viewModel.uiState.rename.errorMessage == "boom")
        #expect(viewModel.uiState.sessionTitle == "Title")
    }

    @Test func sessionUpdatedEvent_appliesTitleForOpenSession() async {
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(eventStream: eventStream)
        defer { viewModel.stopObserving() }

        // Let SessionInfo finish subscribing to the fake event stream.
        try? await Task.sleep(for: .milliseconds(50))

        eventStream.emit(
            OpencodeEvent(
                type: "session.updated",
                sessionID: "ses-1",
                rawProperties: [
                    "info": .dictionary([
                        "id": .string("ses-1"),
                        "title": .string("Live title"),
                    ]),
                ]
            )
        )
        let updated = await waitUntil { viewModel.uiState.sessionTitle == "Live title" }
        #expect(updated)
        #expect(viewModel.uiState.sessionTitle == "Live title")
    }

    @Test func cancelRenameSession_hidesDialog() async {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }

        viewModel.openRenameSession()
        viewModel.updateRenameDraft("Draft")
        #expect(viewModel.uiState.rename.isDialogVisible)
        viewModel.cancelRenameSession()

        #expect(!viewModel.uiState.rename.isDialogVisible)
        #expect(viewModel.uiState.sessionTitle == "Title")
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

    @Test(
        .disabled("Quarantined from CI: stress println benchmark, not a correctness gate")
    )
    func benchmark_loadMessages_longMarkdownHistory() async {
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

    @Test func sendPrompt_matchingServerCommand_runsCommandApi() async {
        let repository = FakeMessageRepository()
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: repository,
            commandRepository: commandRepository
        )
        await viewModel.loadMessages()
        viewModel.updateComposerText("/")
        let catalogReady = await waitUntil {
            viewModel.uiState.slashSuggestionsVisible &&
                viewModel.uiState.slashSuggestions.contains { $0.commandName == "init" }
        }
        guard catalogReady else {
            Issue.record("Expected server command catalog to load")
            return
        }

        viewModel.updateComposerText("/init please")
        await viewModel.sendPrompt()

        #expect(commandRepository.runCommandCalled)
        #expect(commandRepository.lastRunOptions?.command == "init")
        #expect(commandRepository.lastRunOptions?.arguments == "please")
        #expect(repository.sendPromptAsyncCalled == false)
        #expect(viewModel.uiState.composer.text == "")
    }

    @Test func sendPrompt_unknownSlash_fallsThroughToPrompt() async {
        let repository = FakeMessageRepository()
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: repository,
            commandRepository: commandRepository
        )
        await viewModel.loadMessages()
        viewModel.updateComposerText("/")
        _ = await waitUntil {
            viewModel.uiState.slashSuggestionsVisible && !viewModel.uiState.slashSuggestions.isEmpty
        }

        viewModel.updateComposerText("/not-a-command hi")
        await viewModel.sendPrompt()

        #expect(commandRepository.runCommandCalled == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(repository.sentOptions?.text == "/not-a-command hi")
    }

    @Test func atSuggestion_selectInsertsMentionAndSendsAgentParts() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()

        viewModel.updateComposerText("@", cursor: 1)
        let catalogReady = await waitUntil {
            viewModel.uiState.atSuggestionsVisible &&
                viewModel.uiState.atSuggestions.contains { $0.agentName == "explore" }
        }
        guard catalogReady else {
            Issue.record("Expected mentionable agent catalog to load")
            return
        }

        viewModel.selectAtSuggestion(agentName: "explore")
        #expect(viewModel.uiState.composer.text == "@explore ")
        #expect(viewModel.uiState.composer.agentMentions.count == 1)

        await viewModel.sendPrompt()

        #expect(repository.sendPromptAsyncCalled)
        #expect(repository.sentOptions?.text == "@explore")
        #expect(repository.sentOptions?.agentMentions.count == 1)
        #expect(repository.sentOptions?.agentMentions.first?.name == "explore")
        #expect(viewModel.uiState.composer.text == "")
        #expect(viewModel.uiState.composer.agentMentions.isEmpty)
    }

    @Test func atSuggestion_winsOverSlashSuggestions() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository())
        await viewModel.loadMessages()

        viewModel.updateComposerText("/init @", cursor: 7)
        let ready = await waitUntil {
            viewModel.uiState.atSuggestionsVisible
        }
        #expect(ready)
        #expect(viewModel.uiState.slashSuggestionsVisible == false)
    }

    @Test func sendPrompt_serverCommand_doesNotAttachMentionParts() async {
        let repository = FakeMessageRepository()
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: repository,
            commandRepository: commandRepository
        )
        await viewModel.loadMessages()

        viewModel.updateComposerText("/init ", cursor: 6)
        viewModel.updateComposerText("/init @", cursor: 7)
        _ = await waitUntil { viewModel.uiState.atSuggestionsVisible }
        viewModel.selectAtSuggestion(agentName: "explore")
        #expect(viewModel.uiState.composer.agentMentions.count == 1)
        viewModel.updateComposerText("/init @explore please", cursor: 22)

        await viewModel.sendPrompt()

        #expect(commandRepository.runCommandCalled)
        #expect(repository.sendPromptAsyncCalled == false)
        #expect(commandRepository.lastRunOptions?.command == "init")
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

    @Test func attachments_canBeAddedAndRemovedFromComposer() async {
        let viewModel = makeViewModel()
        await viewModel.loadMessages()
        let attachment = testAttachment(id: "one", filename: "notes.txt")

        viewModel.addAttachments([attachment])
        #expect(viewModel.uiState.composer.attachments == [attachment])

        viewModel.removeAttachment(id: attachment.id)
        #expect(viewModel.uiState.composer.attachments.isEmpty)
    }

    @Test func attach_disabledForTextOnlyModelAndStripsUnsupportedPendingAttachments() async {
        let textOnly = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "text-only",
            modelName: "Text Only",
            variants: [],
            contextLimitTokens: 128_000,
            supportsImageInput: false,
            supportsPdfInput: false
        )
        let vision = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "vision",
            modelName: "Vision",
            variants: [],
            contextLimitTokens: 128_000,
            supportsImageInput: true,
            supportsPdfInput: false
        )
        let viewModel = makeViewModel(
            modelRepository: FakeModelRepository(
                catalog: ModelSelectionCatalog(options: [vision, textOnly], serverDefault: vision)
            )
        )
        await viewModel.loadMessages()
        #expect(viewModel.uiState.composer.canAttach)
        #expect(viewModel.uiState.composer.canAttachPhotos)

        let image = testAttachment(id: "img", filename: "shot.png", kind: .image, mime: "image/png")
        let pdf = testAttachment(id: "pdf", filename: "brief.pdf", kind: .pdf, mime: "application/pdf")
        let text = testAttachment(id: "txt", filename: "notes.txt", kind: .text, mime: "text/plain")
        viewModel.addAttachments([image, pdf, text])
        #expect(viewModel.uiState.composer.attachments.map(\.id) == ["img", "txt"])
        #expect(viewModel.uiState.snackbar?.message == "This model does not support PDF attachments.")
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)

        viewModel.selectModel(textOnly)
        #expect(viewModel.uiState.composer.canAttach == false)
        #expect(viewModel.uiState.composer.attachments.map(\.id) == ["txt"])
    }

    @Test func addAttachmentData_acceptsSupportedTypesAndCoercesTextLikeFiles() {
        let viewModel = makeViewModel()
        let cases: [(String, String?, String, AttachmentKind)] = [
            ("photo.png", "image/png", "image/png", .image),
            ("photo.jpg", "image/jpeg", "image/jpeg", .image),
            ("animation.gif", "image/gif", "image/gif", .image),
            ("preview.webp", "image/webp", "image/webp", .image),
            ("brief.pdf", "application/pdf", "application/pdf", .pdf),
            ("notes.md", "text/markdown", "text/plain", .text),
            ("events.jsonl", "application/json", "text/plain", .text),
        ]

        for (filename, declaredMime, expectedMime, expectedKind) in cases {
            viewModel.addAttachmentData(Data("content".utf8), filename: filename, declaredMime: declaredMime)
            let attachment = viewModel.uiState.composer.attachments.last
            #expect(attachment?.filename == filename)
            #expect(attachment?.mime == expectedMime)
            #expect(attachment?.kind == expectedKind)
            #expect(attachment?.dataURL == "data:\(expectedMime);base64,Y29udGVudA==")
        }
        #expect(viewModel.uiState.composer.attachments.count == cases.count)
    }

    @Test func addAttachmentData_rejectsUnsupportedBinaryAndPreservesDraft() {
        let viewModel = makeViewModel()
        viewModel.updateComposerText("Keep this draft")

        viewModel.addAttachmentData(Data([0, 1, 2]), filename: "archive.zip", declaredMime: "application/zip")

        #expect(viewModel.uiState.composer.attachments.isEmpty)
        #expect(viewModel.uiState.composer.text == "Keep this draft")
        #expect(viewModel.uiState.snackbar?.message == "Only images, PDFs, or text files can be attached here.")
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)
    }

    @Test func sendPrompt_attachmentOnly_sendsAndRendersOptimistically() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let attachment = testAttachment(id: "one", filename: "notes.txt")
        viewModel.addAttachments([attachment])

        await viewModel.sendPrompt()

        #expect(repository.sentOptions?.text.isEmpty == true)
        #expect(repository.sentOptions?.attachments == [attachment])
        #expect(viewModel.uiState.composer.attachments.isEmpty)
        #expect(viewModel.uiState.messages.contains { row in
            row.isPending && row.blocks.contains {
                guard case let .attachment(_, file) = $0 else { return false }
                return file.filename == "notes.txt"
            }
        })
    }

    @Test func sendPrompt_httpFailure_restoresDraftAndAttachments() async {
        let repository = FakeMessageRepository(shouldFailSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let attachment = testAttachment(id: "one", filename: "notes.txt")
        viewModel.updateComposerText("Retry me")
        viewModel.addAttachments([attachment])

        await viewModel.sendPrompt()

        #expect(viewModel.uiState.composer.text == "Retry me")
        #expect(viewModel.uiState.composer.attachments == [attachment])
    }

    @Test func sendPrompt_httpFailure_doesNotOverwriteNewDraftEdits() async {
        let repository = FakeMessageRepository(shouldFailSend: true, holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.updateComposerText("Submitted")
        let task = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }
        viewModel.updateComposerText("New draft")
        repository.releaseSend()
        await task.value

        #expect(viewModel.uiState.composer.text == "New draft")
    }

    @Test func sendPrompt_httpFailure_restoresSubmittedAttachmentsAlongsideNewSelections() async {
        let repository = FakeMessageRepository(shouldFailSend: true, holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let submitted = testAttachment(id: "submitted", filename: "submitted.txt")
        let newlySelected = testAttachment(id: "new", filename: "new.txt")
        viewModel.addAttachments([submitted])

        let task = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }
        viewModel.addAttachments([newlySelected])
        repository.releaseSend()
        await task.value

        #expect(viewModel.uiState.composer.attachments == [submitted, newlySelected])
    }

    @Test func sendPrompt_successPreservesVisibleDraftUntilAcceptedThenClearsOnlySubmittedValues() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true, holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let submitted = testAttachment(id: "submitted", filename: "submitted.txt")
        let newlySelected = testAttachment(id: "new", filename: "new.txt")
        viewModel.updateComposerText("Submitted")
        viewModel.addAttachments([submitted])

        let task = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }

        #expect(viewModel.uiState.composer.text == "Submitted")
        #expect(viewModel.uiState.composer.attachments == [submitted])

        viewModel.updateComposerText("New draft")
        viewModel.addAttachments([newlySelected])
        repository.releaseSend()
        await task.value

        #expect(viewModel.uiState.composer.text == "New draft")
        #expect(viewModel.uiState.composer.attachments == [newlySelected])
    }

    @Test func sendPrompt_successPreservesAttachmentReselectedDuringFlight() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true, holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let attachment = testAttachment(id: "same", filename: "notes.txt")
        viewModel.addAttachments([attachment])

        let task = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }
        viewModel.removeAttachment(id: attachment.id)
        viewModel.addAttachments([attachment])
        repository.releaseSend()
        await task.value

        #expect(viewModel.uiState.composer.attachments == [attachment])
    }

    @Test func sessionError_surfacesWithoutRestoringAcceptedAttachments() async {
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.addAttachments([testAttachment(id: "one", filename: "large.png")])
        await viewModel.sendPrompt()
        #expect(viewModel.uiState.composer.attachments.isEmpty)

        eventStream.emit(
            OpencodeEvent(
                type: "session.error",
                sessionID: "ses-1",
                rawProperties: [
                    "error": .dictionary([
                        "name": .string("MessageOutputLengthError"),
                        "data": .dictionary(["message": .string("Image exceeds the maximum size")]),
                    ]),
                ]
            )
        )
        await waitUntil { viewModel.uiState.snackbar?.message == "Image exceeds the maximum size" }
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)

        #expect(viewModel.uiState.composer.attachments.isEmpty)
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
        #expect(viewModel.uiState.snackbar?.message != nil)
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)
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

    @Test func quietRefreshDuringAttachmentOnlySend_requiresNewMatchingServerMessage() async {
        let attachment = testAttachment(id: "repeat", filename: "repeat.txt")
        let file = FilePart(
            id: attachment.id,
            filename: attachment.filename,
            mime: attachment.mime,
            kind: attachment.kind,
            dataURL: attachment.dataURL
        )
        let existing = Message(
            id: "msg-existing",
            role: .user,
            text: "",
            parts: [.file(file)],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1
        )
        let repository = FakeMessageRepository(messages: [existing], holdSend: true)
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(messageRepository: repository, eventStream: eventStream)
        await viewModel.loadMessages()
        viewModel.addAttachments([attachment])

        let sendTask = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await waitUntil { viewModel.uiState.messages.count == 2 }
        #expect(viewModel.uiState.messages.contains { $0.isPending })

        repository.messages = [
            existing,
            Message(
                id: "msg-new",
                role: .user,
                text: "",
                parts: [.file(file)],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        let reconciled = await waitUntil(timeoutMs: 5_000) {
            viewModel.uiState.messages.count == 2 && !viewModel.uiState.messages.contains(where: \.isPending)
        }

        #expect(reconciled)
        #expect(!viewModel.uiState.messages.contains { $0.isPending })
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
            parts: [.tool(ToolPart(id: "tool-1", toolName: "bash", status: .completed))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let viewModel = makeViewModel()
        let row = viewModel.row(from: message)
        #expect(row?.isStreaming == false)
        guard case .toolCard(_, let card) = row?.blocks.first else {
            Issue.record("Expected tool card block")
            return
        }
        #expect(card.title == "Shell")
    }

    @Test func rowFromUserMessage_preservesOrderedTextAndAttachmentBlocks() {
        let first = FilePart(
            id: "first",
            filename: "first.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,Rmlyc3Q="
        )
        let second = FilePart(
            id: "second",
            filename: "second.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,U2Vjb25k"
        )
        let message = Message(
            id: "msg-ordered",
            role: .user,
            text: "Before\nAfter",
            parts: [
                .text(TextPart(id: "t1", text: "Before", isStreaming: false)),
                .file(first),
                .text(TextPart(id: "t2", text: "After", isStreaming: false)),
                .file(second),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )

        let row = makeViewModel().row(from: message)

        #expect(row?.blocks == [
            .text(blockID: PresentationBlockId.direct("t1"), text: "Before", renderedMarkdown: nil),
            .attachment(blockID: PresentationBlockId.direct("first"), file: first),
            .text(blockID: PresentationBlockId.direct("t2"), text: "After", renderedMarkdown: nil),
            .attachment(blockID: PresentationBlockId.direct("second"), file: second),
        ])
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

    @Test func openCommands_showsSheetAndDismissesSuggestionsKeepingText() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository())
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.updateComposerText("/in")
        #expect(viewModel.uiState.slashSuggestionsVisible)

        viewModel.openCommands()

        #expect(viewModel.uiState.commandsSheetVisible)
        #expect(viewModel.uiState.slashSuggestionsVisible == false)
        #expect(viewModel.uiState.atSuggestionsVisible == false)
        #expect(viewModel.uiState.composer.text == "/in")
    }

    @Test func clientSlashActions_visibility_reflectsCatalogGates() async {
        let emptyMessages = makeViewModel(messageRepository: FakeMessageRepository(messages: []))
        defer { emptyMessages.stopObserving() }
        await emptyMessages.loadMessages()
        #expect(emptyMessages.uiState.visibleClientSlashActions == [.new])

        let withMessages = makeViewModel(messageRepository: FakeMessageRepository())
        defer { withMessages.stopObserving() }
        await withMessages.loadMessages()
        let visible = withMessages.uiState.visibleClientSlashActions
        #expect(visible.contains(.new))
        #expect(visible.contains(.fork))
        #expect(visible.contains(.undo))
        #expect(visible.contains(.compact))
        #expect(visible.contains(.redo) == false)
    }

    @Test func runClientSlashAction_new_createsSessionKeepsDraftNeverRunsCommand() async {
        let sessionRepository = FakeChatSessionRepository(
            createSessionResult: Session(
                id: "ses-2", title: "New session", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            commandRepository: commandRepository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        viewModel.updateComposerText("draft that should survive")
        viewModel.openCommands()

        viewModel.runClientSlashAction(.new)
        await waitUntil { viewModel.uiState.pendingSessionReplace != nil }

        #expect(sessionRepository.createSessionDirectory == "/repo")
        #expect(viewModel.uiState.commandsSheetVisible == false)
        #expect(viewModel.uiState.pendingSessionReplace == ChildSessionNavigationTarget(sessionID: "ses-2", sessionTitle: "New session", worktree: "/repo"))
        #expect(viewModel.uiState.composer.text == "draft that should survive")
        #expect(commandRepository.runCommandCalled == false)

        viewModel.consumePendingSessionReplace()
        #expect(viewModel.uiState.pendingSessionReplace == nil)
    }

    @Test func runClientSlashAction_new_failure_setsActionErrorMessage() async {
        let sessionRepository = FakeChatSessionRepository(createSessionError: .http(statusCode: 500, message: "boom"))
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.new)
        await waitUntil { viewModel.uiState.snackbar?.message != nil }

        #expect(viewModel.uiState.pendingSessionReplace == nil)
        #expect(viewModel.uiState.snackbar?.message == "Couldn't start a new session.")
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)
    }

    @Test func runClientSlashAction_fork_confirmAndOpen_navigatesToForkedSession() async {
        let sessionRepository = FakeChatSessionRepository(
            forkSessionResult: Session(
                id: "ses-3", title: "Original", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            commandRepository: commandRepository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.fork)
        #expect(viewModel.uiState.forkDialogVisible)
        #expect(viewModel.uiState.forkDraftTitle == "Title")
        #expect(viewModel.uiState.commandsSheetVisible == false)

        viewModel.updateForkDraft("Forked copy")
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkResultVisible }

        #expect(sessionRepository.forkSessionRequest == .init(id: "ses-1", atMessageID: nil))
        #expect(sessionRepository.updateRequest == .init(id: "ses-3", title: "Forked copy", directory: "/repo"))
        #expect(viewModel.uiState.forkDialogVisible == false)
        #expect(viewModel.uiState.forkedSessionTitle == "Forked copy")
        #expect(commandRepository.runCommandCalled == false)

        viewModel.chooseForkOpen()
        #expect(viewModel.uiState.forkResultVisible == false)
        #expect(viewModel.uiState.pendingSessionReplace == ChildSessionNavigationTarget(sessionID: "ses-3", sessionTitle: "Forked copy", worktree: "/repo"))
    }

    @Test func runClientSlashAction_fork_stay_dismissesWithoutNavigating() async {
        let sessionRepository = FakeChatSessionRepository(
            forkSessionResult: Session(
                id: "ses-3", title: "Original", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.fork)
        viewModel.updateForkDraft("Forked copy")
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkResultVisible }

        viewModel.chooseForkStay()
        #expect(viewModel.uiState.forkResultVisible == false)
        #expect(viewModel.uiState.pendingSessionReplace == nil)
    }

    @Test func runClientSlashAction_fork_failure_keepsDialogOpenWithError() async {
        let sessionRepository = FakeChatSessionRepository(forkSessionError: .http(statusCode: 500, message: "boom"))
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.fork)
        viewModel.updateForkDraft("Forked copy")
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkErrorMessage != nil }

        #expect(viewModel.uiState.forkDialogVisible)
        #expect(viewModel.uiState.forkSubmitting == false)
        #expect(viewModel.uiState.forkErrorMessage != nil)
    }

    @Test func runClientSlashAction_undo_revertsLastUndoableMessageAndRestoresComposer() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
            Message(id: "msg-3", role: .user, text: "Second", model: nil, mode: nil, sentAtEpochMs: 3),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            commandRepository: commandRepository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        #expect(viewModel.uiState.visibleClientSlashActions.contains(.undo))

        viewModel.runClientSlashAction(.undo)
        await waitUntil { viewModel.uiState.composer.text == "Second" }

        #expect(sessionRepository.revertRequest == .init(id: "ses-1", messageID: "msg-3", partID: nil))
        #expect(sessionRepository.abortCallCount == 0)
        #expect(commandRepository.runCommandCalled == false)
    }

    @Test func runClientSlashAction_undo_whenBusy_abortsBeforeReverting() async {
        let messages = [Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1)]
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        #expect(viewModel.uiState.sessionStatusLabel != nil)

        viewModel.runClientSlashAction(.undo)
        await waitUntil { sessionRepository.revertRequest != nil }

        #expect(sessionRepository.abortCallCount == 1)
        #expect(sessionRepository.revertRequest?.messageID == "msg-1")
    }

    @Test func runClientSlashAction_redo_unrevertsAndClearsComposerRestore() async {
        let sessionRepository = FakeChatSessionRepository(
            getSessionResult: Session(
                id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil,
                updatedAtEpochMs: nil, revert: SessionRevert(messageID: "msg-1")
            )
        )
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.redo) }
        viewModel.restoreComposerText("Second")

        viewModel.runClientSlashAction(.redo)
        await waitUntil { viewModel.uiState.composer.text == "" }

        #expect(sessionRepository.unrevertRequest == "ses-1")
    }

    @Test func runClientSlashAction_redo_withoutActiveRevert_isNoop() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.redo)
        try? await Task.sleep(for: .milliseconds(50))

        #expect(sessionRepository.unrevertRequest == nil)
    }

    @Test func runClientSlashAction_compact_summarizesWithSelectedModelAndRefreshes() async {
        let sessionRepository = FakeChatSessionRepository()
        let commandRepository = FakeCommandRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            commandRepository: commandRepository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        #expect(viewModel.uiState.visibleClientSlashActions.contains(.compact))

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        await waitUntil { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }

        #expect(sessionRepository.summarizeRequest == .init(id: "ses-1", providerID: "anthropic", modelID: "claude-sonnet"))
        #expect(viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess)
        #expect(viewModel.uiState.snackbar?.tone == .default)
        #expect(viewModel.uiState.sessionStatusLabel == nil)
        #expect(commandRepository.runCommandCalled == false)
    }

    @Test func runClientSlashAction_compact_showsCompactingWhileInFlight() async {
        let hold = AsyncGate()
        let sessionRepository = FakeChatSessionRepository(summarizeHold: hold)
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        await waitUntil { viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel }

        #expect(viewModel.uiState.workingIndicatorLabel == SessionCompactChrome.statusLabel)

        await hold.open()
        await waitUntil { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }
        #expect(viewModel.uiState.sessionStatusLabel == nil)
        #expect(viewModel.uiState.workingIndicatorLabel == nil)
    }

    @Test func runClientSlashAction_compact_failureClearsCompactingAndShowsError() async {
        let sessionRepository = FakeChatSessionRepository(
            summarizeError: .http(statusCode: 500, message: "boom")
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.compact)
        await waitUntil { viewModel.uiState.snackbar?.message == SessionCompactChrome.failure }

        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.sessionStatusLabel == nil)
    }

    @Test func remoteCompaction_busyWithCompactionPart_showsCompactingWithoutLocalFlag() async {
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(
                    id: "u1",
                    role: .user,
                    text: "",
                    parts: [.compaction(CompactionPart(id: "c1", auto: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: 1
                )
            ]),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel }
        #expect(viewModel.uiState.workingIndicatorLabel == SessionCompactChrome.statusLabel)
    }

    private func makeViewModel(
        messageRepository: (any MessageRepository)? = nil,
        eventStream: FakeForegroundEventStream? = nil,
        sessionModelSelectionStore: (any SessionModelSelectionStore)? = nil,
        modelRepository: FakeModelRepository = FakeModelRepository(),
        commandRepository: FakeCommandRepository = FakeCommandRepository(),
        agentRepository: FakeAgentRepository = FakeAgentRepository(),
        sessionRepository: FakeChatSessionRepository? = nil,
        permissionLiveStore: (any PermissionLiveStore)? = nil,
        questionLiveStore: (any QuestionLiveStore)? = nil
    ) -> SessionChatViewModel {
        let eventStream = eventStream ?? FakeForegroundEventStream()
        // Mutations (send/rename/fork/new/permissions) require Connected; default fakes start Idle.
        eventStream.setConnectionState(.connected)
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
            commandRepository: commandRepository,
            agentRepository: agentRepository,
            sessionRepository: sessionRepository,
            permissionLiveStore: permissionLiveStore,
            questionLiveStore: questionLiveStore,
            sessionReadyStore: EmptySessionReadyStore(),
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: eventStream,
            sessionModelSelectionStore: selectionStore,
            activeConnectionManager: activeConnectionManager,
            newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                store: selectionStore,
                modelRepository: modelRepository
            )
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

    private func testAttachment(
        id: String,
        filename: String,
        kind: AttachmentKind = .text,
        mime: String = "text/plain"
    ) -> OpenCode_Mobile.Attachment {
        OpenCode_Mobile.Attachment(
            id: id,
            filename: filename,
            mime: mime,
            kind: kind,
            dataURL: "data:\(mime);base64,SGVsbG8="
        )
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
                    case .cacheSyncConfirmed:
                        continuation.yield(.cacheSyncConfirmed)
                    case .messages(let messages, let isFromCache):
                        let prepared = await MessageContentPreparer.shared.prepareMessages(messages, previousByID: previousByID)
                        previousByID = Dictionary(uniqueKeysWithValues: prepared.map { ($0.id, $0) })
                        continuation.yield(.messages(prepared, isFromCache: isFromCache))
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
        publish?(.messages(messages, isFromCache: false))
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
    private var sendReleaseRequested = false

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
        if let sendContinuation {
            sendContinuation.resume()
            self.sendContinuation = nil
        } else {
            sendReleaseRequested = true
        }
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
                    continuation.yield(.messages(prepared, isFromCache: false))
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
            if holdSend, !sendReleaseRequested {
                await withCheckedContinuation { sendContinuation = $0 }
            }
            throw MessageError.transport("send failed")
        } else if holdSend, !sendReleaseRequested {
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

private final class FakeCommandRepository: CommandRepository, @unchecked Sendable {
    var commands: [ServerCommand]
    var listCommandsCalled = false
    var runCommandCalled = false
    var lastRunOptions: CommandRunOptions?
    var lastRunSessionID: String?

    init(
        commands: [ServerCommand] = [
            ServerCommand(name: "init", description: "Create AGENTS.md", source: .command),
            ServerCommand(name: "my-skill", description: "A skill", source: .skill),
        ]
    ) {
        self.commands = commands
    }

    func listCommands(directory: String?) async throws -> [ServerCommand] {
        listCommandsCalled = true
        return commands
    }

    func runCommand(sessionID: String, options: CommandRunOptions, directory: String?) async throws {
        runCommandCalled = true
        lastRunSessionID = sessionID
        lastRunOptions = options
    }
}

private final class FakeAgentRepository: AgentRepository, @unchecked Sendable {
    var agents: [MentionableAgent]

    init(
        agents: [MentionableAgent] = [
            MentionableAgent(name: "explore", description: "Read-only explorer"),
            MentionableAgent(name: "general", description: "General helper"),
        ]
    ) {
        self.agents = agents
    }

    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent] {
        agents
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

    struct UpdateRequest: Equatable {
        let id: String
        let title: String?
        let directory: String?
    }

    struct LifecycleRequest: Equatable {
        let id: String
        let directory: String?
    }

    struct ForkRequest: Equatable {
        let id: String
        let atMessageID: String?
    }

    struct RevertRequest: Equatable {
        let id: String
        let messageID: String
        let partID: String?
    }

    struct SummarizeRequest: Equatable {
        let id: String
        let providerID: String
        let modelID: String
    }

    private let sessions: [Session]
    private let pendingPermissions: [PermissionRequest]
    private let pendingQuestions: [QuestionRequest]
    private let permissionResponseError: SessionError?
    private let questionResponseError: SessionError?
    private let listSessionsError: SessionError?
    private let sessionDiffs: [FileDiff]
    private let sessionDiffError: SessionError?
    private let sessionDiffProvider: (@Sendable (String?) async throws -> [FileDiff])?
    private let sessionStatusType: String
    private let updateSessionError: SessionError?
    private let archiveError: SessionError?
    private let deleteError: SessionError?
    private let deleteResult: Bool
    private let archiveHold: AsyncGate?
    private let deleteHold: AsyncGate?
    private let questionHold: AsyncGate?
    private(set) var responseDirectory: String?
    private(set) var responsePermissionID: String?
    private(set) var responseSessionID: String?
    private(set) var pendingPermissionLoads = 0
    private(set) var pendingQuestionLoads = 0
    private(set) var repliedQuestionID: String?
    private(set) var rejectedQuestionID: String?
    private(set) var diffRequest: DiffRequest?
    private(set) var updateRequest: UpdateRequest?
    private(set) var archiveRequest: LifecycleRequest?
    private(set) var archiveCallCount = 0
    private(set) var deleteRequest: LifecycleRequest?
    private(set) var deleteCallCount = 0
    var getSessionResult: Session?
    private(set) var getSessionRequest: LifecycleRequest?
    var createSessionResult: Session?
    private(set) var createSessionDirectory: String?
    private(set) var createSessionCallCount = 0
    var forkSessionResult: Session?
    private(set) var forkSessionRequest: ForkRequest?
    private(set) var abortCallCount = 0
    private(set) var revertRequest: RevertRequest?
    private(set) var unrevertRequest: String?
    private(set) var summarizeRequest: SummarizeRequest?
    private let getSessionError: SessionError?
    private let createSessionError: SessionError?
    private let forkSessionError: SessionError?
    private let revertError: SessionError?
    private let unrevertError: SessionError?
    private let summarizeError: SessionError?
    private let summarizeHold: AsyncGate?

    init(
        sessions: [Session] = [],
        pendingPermissions: [PermissionRequest] = [],
        pendingQuestions: [QuestionRequest] = [],
        permissionResponseError: SessionError? = nil,
        questionResponseError: SessionError? = nil,
        listSessionsError: SessionError? = nil,
        sessionDiffs: [FileDiff] = [],
        sessionDiffError: SessionError? = nil,
        sessionStatusType: String = "idle",
        sessionDiffProvider: (@Sendable (String?) async throws -> [FileDiff])? = nil,
        updateSessionError: SessionError? = nil,
        archiveError: SessionError? = nil,
        deleteError: SessionError? = nil,
        deleteResult: Bool = true,
        archiveHold: AsyncGate? = nil,
        deleteHold: AsyncGate? = nil,
        questionHold: AsyncGate? = nil,
        getSessionResult: Session? = nil,
        getSessionError: SessionError? = nil,
        createSessionResult: Session? = nil,
        createSessionError: SessionError? = nil,
        forkSessionResult: Session? = nil,
        forkSessionError: SessionError? = nil,
        revertError: SessionError? = nil,
        unrevertError: SessionError? = nil,
        summarizeError: SessionError? = nil,
        summarizeHold: AsyncGate? = nil
    ) {
        self.sessions = sessions
        self.pendingPermissions = pendingPermissions
        self.pendingQuestions = pendingQuestions
        self.permissionResponseError = permissionResponseError
        self.questionResponseError = questionResponseError
        self.listSessionsError = listSessionsError
        self.sessionDiffs = sessionDiffs
        self.sessionDiffError = sessionDiffError
        self.sessionStatusType = sessionStatusType
        self.sessionDiffProvider = sessionDiffProvider
        self.updateSessionError = updateSessionError
        self.archiveError = archiveError
        self.getSessionResult = getSessionResult
        self.getSessionError = getSessionError
        self.createSessionResult = createSessionResult
        self.createSessionError = createSessionError
        self.forkSessionResult = forkSessionResult
        self.forkSessionError = forkSessionError
        self.revertError = revertError
        self.unrevertError = unrevertError
        self.summarizeError = summarizeError
        self.summarizeHold = summarizeHold
        self.deleteError = deleteError
        self.deleteResult = deleteResult
        self.archiveHold = archiveHold
        self.deleteHold = deleteHold
        self.questionHold = questionHold
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        pendingPermissionLoads += 1
        return pendingPermissions
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        pendingQuestionLoads += 1
        return pendingQuestions
    }

    func listSessions(directory: String?) async throws -> [Session] {
        if let listSessionsError { throw listSessionsError }
        return sessions
    }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        createSessionCallCount += 1
        createSessionDirectory = directory
        if let createSessionError { throw createSessionError }
        return createSessionResult ?? Session(
            id: "new-ses",
            title: title ?? "",
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: nil
        )
    }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        [SessionStatus(sessionID: "ses-1", raw: ["type": sessionStatusType])]
    }
    func getSession(id: String, directory: String?) async throws -> Session {
        getSessionRequest = LifecycleRequest(id: id, directory: directory)
        if let getSessionError { throw getSessionError }
        return getSessionResult ?? Session(
            id: id,
            title: "Title",
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: nil
        )
    }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        updateRequest = UpdateRequest(id: id, title: title, directory: directory)
        if let updateSessionError { throw updateSessionError }
        return Session(
            id: id,
            title: title ?? "",
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: nil
        )
    }
    func archiveSession(id: String, directory: String?) async throws -> Session {
        archiveCallCount += 1
        archiveRequest = LifecycleRequest(id: id, directory: directory)
        if let archiveHold { await archiveHold.wait() }
        if let archiveError { throw archiveError }
        return Session(
            id: id,
            title: "Archived",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: nil,
            archivedAtEpochMs: 1
        )
    }
    func deleteSession(id: String, directory: String?) async throws -> Bool {
        deleteCallCount += 1
        deleteRequest = LifecycleRequest(id: id, directory: directory)
        if let deleteHold { await deleteHold.wait() }
        if let deleteError { throw deleteError }
        return deleteResult
    }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        forkSessionRequest = ForkRequest(id: id, atMessageID: atMessageID)
        if let forkSessionError { throw forkSessionError }
        return forkSessionResult ?? Session(
            id: "forked-ses",
            title: "Untitled",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: nil
        )
    }
    func abortSession(id: String, directory: String?) async throws -> Bool {
        abortCallCount += 1
        return true
    }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        diffRequest = DiffRequest(sessionID: id, messageID: messageID, directory: directory)
        if let sessionDiffProvider { return try await sessionDiffProvider(messageID) }
        if let sessionDiffError { throw sessionDiffError }
        return sessionDiffs
    }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool {
        summarizeRequest = SummarizeRequest(id: id, providerID: providerID, modelID: modelID)
        if let summarizeHold { await summarizeHold.wait() }
        if let summarizeError { throw summarizeError }
        return true
    }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        revertRequest = RevertRequest(id: id, messageID: messageID, partID: partID)
        if let revertError { throw revertError }
        return true
    }
    func unrevertSession(id: String) async throws -> Bool {
        unrevertRequest = id
        if let unrevertError { throw unrevertError }
        return true
    }
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

    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool {
        repliedQuestionID = questionID
        if let questionHold { await questionHold.wait() }
        if let questionResponseError { throw questionResponseError }
        return true
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        rejectedQuestionID = questionID
        if let questionHold { await questionHold.wait() }
        if let questionResponseError { throw questionResponseError }
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

private final class AsyncGate: @unchecked Sendable {
    private var continuations: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { continuation in
            if isOpen {
                continuation.resume()
            } else {
                continuations.append(continuation)
            }
        }
    }

    func open() async {
        isOpen = true
        let pending = continuations
        continuations.removeAll()
        pending.forEach { $0.resume() }
    }
}


private final class FakeQuestionLiveStore: QuestionLiveStore {
    private let requests: [QuestionRequest]

    init(requests: [QuestionRequest]) {
        self.requests = requests
    }

    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]> {
        AsyncStream { continuation in
            continuation.yield(requests)
            continuation.finish()
        }
    }

    func clearCache() {}
}

private func questionRequest(
    id: String,
    sessionID: String
) -> QuestionRequest {
    QuestionRequest(
        id: id,
        sessionID: sessionID,
        questions: [
            QuestionInfo(
                question: "Which approach?",
                header: "Approach",
                options: [QuestionOption(label: "A", description: "First")],
                multiple: false,
                custom: true
            )
        ],
        toolMessageID: nil,
        toolCallID: nil
    )
}
