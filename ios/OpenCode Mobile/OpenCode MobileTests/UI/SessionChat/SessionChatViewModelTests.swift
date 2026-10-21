import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct SessionChatViewModelTests {
    init() {
        // The test host app settles Chip timeline on launch (a fresh simulator
        // resolves on), so start every test from the stored defaults.
        SessionChatDisplayPreferences.shared.clear()
    }

    @Test func expandStepParagraph_publishesExpansionInUiState() {
        let viewModel = makeViewModel(sessionRepository: FakeChatSessionRepository())
        defer { viewModel.stopObserving() }
        #expect(viewModel.uiState.expandedStepParagraphs.isEmpty)

        viewModel.expandStepParagraph("sp:t1", chipCount: 5)
        // A tap with fewer chips than already revealed never shrinks the expansion.
        viewModel.expandStepParagraph("sp:t1", chipCount: 4)
        viewModel.expandStepParagraph("sp:r9", chipCount: 2)
        #expect(viewModel.uiState.expandedStepParagraphs == ["sp:t1": 5, "sp:r9": 2])

        // A later tap on the grown paragraph reveals the new chips too.
        viewModel.expandStepParagraph("sp:t1", chipCount: 9)
        #expect(viewModel.uiState.expandedStepParagraphs == ["sp:t1": 9, "sp:r9": 2])
    }

    // MARK: Project identity (#783)

    private static let serverBaseURL = "http://127.0.0.1:4096/"

    /// Home loaded the main checkout's inventory, which lists the `/wt/tax` worktree.
    private func homeInventoryCache() -> WorktreeInventoryCache {
        let cache = WorktreeInventoryCache()
        cache.setAddedPaths(["/repo"], server: Self.serverBaseURL)
        cache.record(.loaded(["/repo", "/wt/tax"]), addedPath: "/repo", server: Self.serverBaseURL)
        return cache
    }

    private func identity(_ state: SessionChatUiState) -> SessionChatProjectIdentity {
        SessionChatProjectIdentity(
            label: state.projectLabel,
            letter: state.projectLetter,
            accent: state.projectLetterAccent,
            isWorktreeCheckout: state.worktreeCheckout
        )
    }

    @Test func projectIdentity_worktreeSessionNamesItsCheckoutOnTheFirstFrame() async {
        let worktrees = GatedWorktreeRepository(load: .loaded(directories: ["/repo", "/wt/tax"], listsSessions: false))
        let viewModel = makeViewModel(
            worktree: "/wt/tax",
            connectionProfileStore: AddedPathsConnectionProfileStore(added: ["/repo"]),
            worktreeRepository: worktrees,
            worktreeInventoryCache: homeInventoryCache()
        )
        defer {
            worktrees.open()
            viewModel.stopObserving()
        }

        let first = viewModel.uiState
        #expect(first.worktreeCheckout)
        #expect(first.projectLabel == "tax")
        #expect(first.projectLetter == nil)
        #expect(first.projectLetterAccent == nil)

        // The server agrees: nothing changes once the refresh lands.
        worktrees.open()
        #expect(await waitUntil { worktrees.answered >= 1 })
        try? await Task.sleep(for: .milliseconds(50))
        #expect(identity(viewModel.uiState) == identity(first))
    }

    @Test func projectIdentity_mainCheckoutSessionKeepsItsLetterFromTheFirstFrame() async {
        let worktrees = GatedWorktreeRepository(load: .loaded(directories: ["/repo", "/wt/tax"], listsSessions: false))
        let viewModel = makeViewModel(
            worktree: "/repo",
            connectionProfileStore: AddedPathsConnectionProfileStore(added: ["/repo"]),
            worktreeRepository: worktrees,
            worktreeInventoryCache: homeInventoryCache()
        )
        defer {
            worktrees.open()
            viewModel.stopObserving()
        }

        let first = viewModel.uiState
        #expect(first.worktreeCheckout == false)
        #expect(first.projectLabel == "repo")
        #expect(first.projectLetter == "R")
        #expect(first.projectLetterAccent == letterAccent(for: "/repo"))

        worktrees.open()
        #expect(await waitUntil { worktrees.answered >= 1 })
        try? await Task.sleep(for: .milliseconds(50))
        #expect(identity(viewModel.uiState) == identity(first))
    }

    @Test func projectIdentity_failedRefreshKeepsTheCheckout() async {
        let worktrees = GatedWorktreeRepository(load: .failed)
        let viewModel = makeViewModel(
            worktree: "/wt/tax",
            connectionProfileStore: AddedPathsConnectionProfileStore(added: ["/repo"]),
            worktreeRepository: worktrees,
            worktreeInventoryCache: homeInventoryCache()
        )
        defer { viewModel.stopObserving() }

        worktrees.open()
        #expect(await waitUntil { worktrees.answered >= 1 })
        try? await Task.sleep(for: .milliseconds(50))
        #expect(viewModel.uiState.worktreeCheckout)
        #expect(viewModel.uiState.projectLabel == "tax")
    }

    @Test func projectIdentity_coldOpenResolvesAndSharesTheInventoryForTheNextOpen() async {
        let cache = WorktreeInventoryCache()
        let worktrees = GatedWorktreeRepository(load: .loaded(directories: ["/repo", "/wt/tax"], listsSessions: false))
        let store = AddedPathsConnectionProfileStore(added: ["/repo"])
        let viewModel = makeViewModel(
            worktree: "/wt/tax",
            connectionProfileStore: store,
            worktreeRepository: worktrees,
            worktreeInventoryCache: cache
        )
        defer { viewModel.stopObserving() }

        // Nothing loaded yet (Home never ran): the folder with its letter until the server answers.
        #expect(viewModel.uiState.worktreeCheckout == false)
        #expect(viewModel.uiState.projectLabel == "tax")
        worktrees.open()
        #expect(await waitUntil { viewModel.uiState.worktreeCheckout })
        #expect(viewModel.uiState.projectLabel == "tax")

        let reopened = makeViewModel(
            worktree: "/wt/tax",
            connectionProfileStore: store,
            worktreeRepository: worktrees,
            worktreeInventoryCache: cache
        )
        defer { reopened.stopObserving() }
        #expect(reopened.uiState.worktreeCheckout)
        #expect(reopened.uiState.projectLabel == "tax")
    }

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

    @Test func permissionSnapshot_fetchesSessionTreeOnlyForUnknownSubagentSessions() async throws {
        let sessionRepository = FakeChatSessionRepository(
            sessions: [
                Session(id: "ses-1", title: "Title", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: nil),
                Session(id: "child", title: "Child", slug: nil, projectID: nil, directory: "/repo", parentID: "ses-1", updatedAtEpochMs: nil),
            ]
        )
        let permissionLiveStore = ScriptedPermissionLiveStore()
        let viewModel = makeViewModel(
            sessionRepository: sessionRepository,
            permissionLiveStore: permissionLiveStore
        )
        defer { viewModel.stopObserving() }

        permissionLiveStore.emit([permissionRequest(id: "permission-root", sessionID: "ses-1")])
        try #require(await waitUntil { viewModel.uiState.permissionRequest?.id == "permission-root" })
        #expect(sessionRepository.listSessionsCallCount == 0)

        permissionLiveStore.emit([
            permissionRequest(id: "permission-root", sessionID: "ses-1"),
            permissionRequest(id: "permission-child", sessionID: "child"),
        ])
        try #require(await waitUntil { viewModel.uiState.permissionQueueCount == 2 })
        #expect(sessionRepository.listSessionsCallCount == 1)

        permissionLiveStore.emit([permissionRequest(id: "permission-child", sessionID: "child")])
        try #require(await waitUntil { viewModel.uiState.permissionRequest?.id == "permission-child" })
        #expect(sessionRepository.listSessionsCallCount == 1)
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

    @Test func sessionDeletedEvent_leavesOpenSessionOnly() async {
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(eventStream: eventStream)
        defer { viewModel.stopObserving() }

        // Let SessionInfo finish subscribing to the fake event stream.
        try? await Task.sleep(for: .milliseconds(50))

        // v1 shape: the id is only in info.id.
        eventStream.emit(
            OpencodeEvent(
                type: "session.deleted",
                sessionID: nil,
                rawProperties: ["info": .dictionary(["id": .string("ses-other")])]
            )
        )
        let leftEarly = await waitUntil(timeoutMs: 200) { viewModel.uiState.shouldLeaveSession }
        #expect(!leftEarly)

        eventStream.emit(
            OpencodeEvent(
                type: "session.deleted",
                sessionID: nil,
                rawProperties: ["info": .dictionary(["id": .string("ses-1")])]
            )
        )
        let left = await waitUntil { viewModel.uiState.shouldLeaveSession }
        #expect(left)
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

    @Test func completedLatestTurnDoesNotFetchReviewChangesDiffOnBind() async {
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
        try? await Task.sleep(for: .milliseconds(50))

        #expect(viewModel.uiState.reviewChangesSummary == nil)
        #expect(sessionRepository.diffRequest == nil)
    }

    @Test func v2DoesNotLoadLastTurnReviewChangesSummary() async {
        let sessionRepository = FakeChatSessionRepository(
            sessionDiffs: [
                FileDiff(path: "one.swift", patch: nil, additions: 1, deletions: 2, status: .modified),
            ]
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(id: "user-1", role: .user, text: "Change it", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(id: "assistant-1", role: .assistant, text: "Done", model: nil, mode: nil, sentAtEpochMs: 2),
            ]),
            sessionRepository: sessionRepository,
            serverProtocol: .v2
        )
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        try? await Task.sleep(for: .milliseconds(50))

        #expect(viewModel.uiState.reviewChangesSummary == nil)
        #expect(viewModel.uiState.supportsSessionMessageDiff == false)
        #expect(sessionRepository.diffRequest == nil)
    }

    @Test func newerLatestTurnCancelsStaleReviewChangesSummary() async {
        let region = SessionChatReviewChanges(
            sessionID: "ses-1",
            worktree: "/repo"
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

        #expect(region.state.messageID == "user-2")
        #expect(region.state.summary == nil)
    }

    @Test func publishedLastTurnSummaryStaysOnRegionWithoutBindFetch() async {
        let store = LastTurnReviewChangesStore()
        let region = SessionChatReviewChanges(
            sessionID: "ses-1",
            worktree: "/repo",
            lastTurnReviewChangesStore: store
        )
        region.updateCandidate(
            messages: [reviewChangesRow(id: "user-1")],
            hasResolvedSessionStatus: true,
            isTurnActive: false,
            isSending: false
        )

        #expect(region.state.summary == nil)

        store.publish(
            sessionID: "ses-1",
            messageID: "user-1",
            summary: ReviewChangesSummary(fileCount: 2, additions: 4, deletions: 1, messageID: "user-1")
        )

        #expect(region.state.summary == ReviewChangesSummary(fileCount: 2, additions: 4, deletions: 1, messageID: "user-1"))
        #expect(region.state.messageID == "user-1")
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
        try? await Task.sleep(for: .milliseconds(50))

        #expect(sessionRepository.diffRequest == nil)
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
        let loaded = await waitUntil { viewModel.uiState.timeline == .ready }
        #expect(loaded)
        viewModel.updateComposerText("New prompt")
        await viewModel.sendPrompt()
        let ready = await waitUntil {
            viewModel.uiState.timeline == .ready
                && viewModel.uiState.isSending == false
                && viewModel.uiState.messages.contains { $0.text == "New prompt" && $0.isPending }
        }
        #expect(ready)
        let messages = viewModel.uiState.messages
        #expect(viewModel.uiState.isSending == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(repository.sentOptions?.model?.providerID == "anthropic")
        #expect(repository.sentOptions?.model?.modelID == "claude-sonnet")
        #expect(repository.sentOptions?.variant == nil)
        #expect(repository.sentOptions?.mode == "build")
        #expect(messages.map(\.text) == ["Hello", "New prompt"])
        #expect(messages.contains { $0.text == "New prompt" && $0.isPending })
    }

    @Test func sendPrompt_whileConnecting_isQueuedThenFlushedOnLive() async {
        let repository = FakeMessageRepository()
        let liveUpdates = FixedLiveUpdates(
            chrome: LiveUpdatesChrome(label: .connecting, canMutate: false, composerEnabled: true)
        )
        let viewModel = makeViewModel(messageRepository: repository, liveUpdates: liveUpdates)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        viewModel.updateComposerText("Queued prompt")
        let sendTask = Task { await viewModel.sendPrompt() }
        let queued = await waitUntil {
            viewModel.uiState.pendingSend != nil
                && viewModel.uiState.messages.contains { $0.text == "Queued prompt" && $0.isPending }
        }
        #expect(queued)
        #expect(!repository.sendPromptAsyncCalled)

        liveUpdates.set(LiveUpdatesChrome(label: .live, canMutate: true, composerEnabled: true))
        await sendTask.value
        #expect(repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.pendingSend == nil)
    }

    @Test func sendPrompt_whileConnecting_timesOutToFailedAfter8s() async {
        let repository = FakeMessageRepository()
        let liveUpdates = FixedLiveUpdates(
            chrome: LiveUpdatesChrome(label: .connecting, canMutate: false, composerEnabled: true)
        )
        let clock = ChatTestSyncClock(now: 1_000)
        let viewModel = makeViewModel(
            messageRepository: repository,
            liveUpdates: liveUpdates,
            clock: clock
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        viewModel.updateComposerText("Queued prompt")
        let sendTask = Task { await viewModel.sendPrompt() }
        let queued = await waitUntil { viewModel.uiState.pendingSend != nil }
        #expect(queued)
        try? await clock.awaitWaiter(milliseconds: Int(SessionActivityPolicy.pendingSendTimeoutMs))
        clock.release(milliseconds: Int(SessionActivityPolicy.pendingSendTimeoutMs))
        await sendTask.value
        #expect(!repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.pendingSend == nil)
        #expect(viewModel.uiState.snackbar?.message == "Couldn't send. Retry when the connection is live.")
        #expect(viewModel.uiState.messages.allSatisfy { !$0.isPending })
    }

    @Test func abortSession_setsOptimisticIdle() async {
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.sessionStatusLabel == "Working" }
        await viewModel.abortSession()
        await waitUntil { viewModel.uiState.sessionStatusLabel == nil }
        #expect(viewModel.uiState.sessionStatusLabel == nil)
    }

    @Test func sendPrompt_postSendReloadMatchingUser_dropsOptimistic() async {
        let repository = FakeMessageRepository(echoSentPrompt: true)
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
        #expect(messages.map(\.text) == ["Hello", "New prompt"])
        #expect(!messages.contains { $0.isPending })
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

    @Test func sendPrompt_skillSlash_sendsPromptWithSkillId() async {
        let repository = FakeMessageRepository()
        let commandRepository = FakeCommandRepository(
            commands: [
                ServerCommand(name: "init", description: "Create AGENTS.md", source: .command),
                ServerCommand(name: "OpenCode", description: "Ask about OpenCode", source: .skill, skillID: "opencode"),
            ]
        )
        let viewModel = makeViewModel(
            messageRepository: repository,
            commandRepository: commandRepository
        )
        await viewModel.loadMessages()
        viewModel.updateComposerText("/OpenCode check the error")
        await viewModel.sendPrompt()

        #expect(commandRepository.runCommandCalled == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(repository.sentOptions?.text == "check the error")
        #expect(repository.sentOptions?.skillIDs == ["opencode"])
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

    @Test func atSuggestion_selectsProjectFileAndSendsReferenceNotAttachment() async {
        let repository = FakeMessageRepository()
        let files = StubProjectFilesRepository(
            entries: [ProjectEntry(name: "App.kt", relativePath: "src/App.kt", isDirectory: false)]
        )
        let viewModel = makeViewModel(messageRepository: repository, projectFilesRepository: files)
        viewModel.fileSearchDelayMs = 0
        await viewModel.loadMessages()

        viewModel.updateComposerText("see @App", cursor: 8)
        let ready = await waitUntil {
            viewModel.uiState.atSuggestions.contains { $0.filePath == "src/App.kt" }
        }
        guard ready else {
            Issue.record("Expected project file suggestion")
            return
        }

        viewModel.selectAtSuggestion(agentName: "file:src/App.kt")
        #expect(viewModel.uiState.composer.text == "see @src/App.kt ")
        #expect(viewModel.uiState.composer.fileMentions.count == 1)
        #expect(viewModel.uiState.composer.agentMentions.isEmpty)
        #expect(viewModel.uiState.composer.attachments.isEmpty)

        await viewModel.sendPrompt()

        #expect(repository.sentOptions?.text == "see @src/App.kt")
        #expect(repository.sentOptions?.fileMentions.map(\.path) == ["src/App.kt"])
        #expect(repository.sentOptions?.attachments.isEmpty == true)
        #expect(repository.sentOptions?.worktree == "/repo")
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

    @Test func sendPrompt_shellDraft_runsCommandInsteadOfPrompt() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.timeline == .ready }
        let rowsBefore = viewModel.uiState.messages.map(\.id)

        viewModel.updateComposerText("!git status")
        #expect(viewModel.uiState.composer.isShellMode)
        #expect(viewModel.uiState.composer.hasSendableContent)
        await viewModel.sendPrompt()
        let ran = await waitUntil { repository.shellRequests.count == 1 }

        #expect(ran)
        let request = repository.shellRequests.first
        #expect(request?.options.command == "git status")
        #expect(request?.options.agent == viewModel.uiState.composer.selectedMode)
        #expect(request?.options.model?.modelID == viewModel.uiState.composer.selectedModel?.option.modelID)
        #expect(request?.directory == "/repo")
        #expect(!repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.composer.text.isEmpty)
        #expect(!viewModel.uiState.isSending)
        // Output arrives through shell events; no optimistic prompt row is added.
        #expect(viewModel.uiState.messages.map(\.id) == rowsBefore)
    }

    @Test func sendPrompt_shellDraftWithPendingRevert_runsWithoutCommittingTheRevert() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let repository = FakeMessageRepository(messages: messages)
        let viewModel = makeViewModel(messageRepository: repository, sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }

        viewModel.updateComposerText("!git status")
        await viewModel.sendPrompt()
        let ran = await waitUntil { repository.shellRequests.count == 1 }

        // Like the TUI and web app, a shell command leaves a staged revert alone.
        #expect(ran)
        #expect(sessionRepository.commitRevertRequests.isEmpty)
        #expect(viewModel.uiState.hasPendingRevert)
    }

    @Test func sendPrompt_bareBang_sendsNothing() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.updateComposerText("!")
        #expect(viewModel.uiState.composer.isShellMode)
        #expect(!viewModel.uiState.composer.hasSendableContent)
        await viewModel.sendPrompt()
        try? await Task.sleep(for: .milliseconds(50))

        #expect(repository.shellRequests.isEmpty)
        #expect(!repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.composer.text == "!")
    }

    @Test func sendPrompt_leadingWhitespaceBeforeBang_sendsPrompt() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.updateComposerText(" !important")
        #expect(!viewModel.uiState.composer.isShellMode)
        await viewModel.sendPrompt()

        #expect(repository.shellRequests.isEmpty)
        #expect(repository.sentOptions?.text == "!important")
    }

    @Test func sendPrompt_multiLineShellDraft_runsWholeScript() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.updateComposerText("!cd app\nls\n")
        await viewModel.sendPrompt()
        _ = await waitUntil { repository.shellRequests.count == 1 }

        #expect(repository.shellRequests.first?.options.command == "cd app\nls")
    }

    @Test func sendPrompt_shellBusyFailure_restoresDraftAndShowsErrorLikeAPrompt() async {
        let repository = FakeMessageRepository()
        repository.shellError = MessageError.http(statusCode: 409, message: "Session is busy")
        let viewModel = makeViewModel(messageRepository: repository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.timeline == .ready }

        viewModel.updateComposerText("!git status")
        await viewModel.sendPrompt()
        let failed = await waitUntil { viewModel.uiState.snackbar?.message == "Session is busy" }

        #expect(failed)
        #expect(repository.shellRequests.count == 1)
        #expect(viewModel.uiState.composer.text == "!git status")
        #expect(viewModel.uiState.snackbar?.tone == .error)
    }

    @Test func exitShellMode_dropsPrefixAndKeepsTheRestOfTheDraft() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository())
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.updateComposerText("!git status")
        viewModel.exitShellMode()

        #expect(viewModel.uiState.composer.text == "git status")
        #expect(!viewModel.uiState.composer.isShellMode)
    }

    @Test func sendPrompt_planMode_sendsPlanAgent() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.selectMode("plan")
        viewModel.updateComposerText("Plan me")
        await viewModel.sendPrompt()
        #expect(repository.sentOptions?.mode == "plan")
    }

    @Test func sendPrompt_customAgent_sendsAgentId() async {
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(
            messageRepository: repository,
            agentRepository: FakeAgentRepository(
                selectableAgents: [
                    SelectableAgent(id: "build", displayName: "Build"),
                    SelectableAgent(id: "review", displayName: "Review"),
                ]
            )
        )
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.selectableAgents.contains { $0.id == "review" } }
        viewModel.selectMode("review")
        viewModel.updateComposerText("Review this")
        await viewModel.sendPrompt()
        #expect(repository.sentOptions?.mode == "review")
        #expect(viewModel.uiState.composer.selectedModeLabel == "Review")
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

    @Test func removeAttachment_deletesOwnedPendingFile() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(AttachmentLimits.storeDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("pending-remove.bin")
        try Data([1, 2, 3]).write(to: file)
        let viewModel = makeViewModel()
        await viewModel.loadMessages()
        let attachment = OpenCode_Mobile.Attachment(
            id: "owned",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "",
            fileURL: file,
            byteCount: 3
        )
        viewModel.addAttachments([attachment])
        viewModel.removeAttachment(id: attachment.id)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        try? FileManager.default.removeItem(at: directory)
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
            #expect(attachment?.dataURL == "")
            #expect(attachment?.fileURL != nil)
            #expect(attachment?.byteCount == Int64(Data("content".utf8).count))
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

    @Test func sendPrompt_attachmentInFlight_showsSendingPercent() async {
        let repository = FakeMessageRepository(holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.addAttachments([testAttachment(id: "one", filename: "notes.txt")])
        let task = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }
        await waitUntil { viewModel.uiState.workingIndicatorLabel == "Sending 25%" }
        repository.releaseSend()
        await task.value
        #expect(!(viewModel.uiState.workingIndicatorLabel ?? "").hasPrefix("Sending"))
        viewModel.stopObserving()
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

    @Test func sendPrompt_optimisticImage_keepsPickPreview() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let preview = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")!
        let attachment = testAttachment(
            id: "img",
            filename: "shot.png",
            kind: .image,
            mime: "image/png",
            previewData: preview
        )
        viewModel.addAttachments([attachment])

        await viewModel.sendPrompt()

        #expect(viewModel.uiState.messages.contains { row in
            row.blocks.contains {
                guard case let .attachment(_, file) = $0 else { return false }
                return file.filename == "shot.png" && file.previewData == preview
            }
        })
        let file = FilePart(
            id: attachment.id,
            filename: attachment.filename,
            mime: attachment.mime,
            kind: attachment.kind,
            dataURL: "",
            previewData: preview
        )
        #expect(AttachmentPresentationMapper.presentation(from: file).image != nil)
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

        #expect(viewModel.uiState.composer.text == "")
        #expect(viewModel.uiState.composer.attachments.isEmpty)

        viewModel.updateComposerText("New draft")
        viewModel.addAttachments([newlySelected])
        repository.releaseSend()
        await task.value

        #expect(viewModel.uiState.composer.text == "New draft")
        #expect(viewModel.uiState.composer.attachments == [newlySelected])
    }

    @Test func sendPrompt_clearsComposerWhileRequestInFlight() async {
        let repository = FakeMessageRepository(holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        viewModel.updateComposerText("/publish-prototype")
        let task = Task { await viewModel.sendPrompt() }
        await waitUntil { viewModel.uiState.isSending }
        #expect(viewModel.uiState.composer.text == "")
        repository.releaseSend()
        await task.value
        #expect(viewModel.uiState.composer.text == "")
        viewModel.stopObserving()
    }

    @Test func composerDraft_restoresTextAndMentionsForSameSessionOnly() async {
        let draftStore = InMemoryComposerDraftStore()
        let first = makeViewModel(messageRepository: FakeMessageRepository(), composerDraftStore: draftStore)
        await first.loadMessages()
        first.updateComposerText("@", cursor: 1)
        first.selectAtSuggestion(agentName: "explore")
        first.flushComposerDraft()
        first.stopObserving()

        let other = makeViewModel(
            messageRepository: FakeMessageRepository(),
            composerDraftStore: draftStore,
            sessionID: "ses-2"
        )
        await other.loadMessages()
        #expect(other.uiState.composer.text == "")
        other.stopObserving()

        let restored = makeViewModel(messageRepository: FakeMessageRepository(), composerDraftStore: draftStore)
        await restored.loadMessages()
        await waitUntil { restored.uiState.composer.text == "@explore " }
        #expect(restored.uiState.composer.text == "@explore ")
        #expect(restored.uiState.composer.agentMentions.map(\.name) == ["explore"])
        restored.stopObserving()
    }

    @Test func composerDraft_stopObservingFlushesPendingDebounce() async {
        let draftStore = InMemoryComposerDraftStore()
        let first = makeViewModel(messageRepository: FakeMessageRepository(), composerDraftStore: draftStore)
        await first.loadMessages()
        first.updateComposerText("pending leave")
        first.stopObserving()

        let restored = makeViewModel(messageRepository: FakeMessageRepository(), composerDraftStore: draftStore)
        await restored.loadMessages()
        await waitUntil { restored.uiState.composer.text == "pending leave" }
        #expect(restored.uiState.composer.text == "pending leave")
        restored.stopObserving()
    }

    @Test func composerDraft_successfulSendClearsStoredDraft() async {
        let draftStore = InMemoryComposerDraftStore()
        let first = makeViewModel(messageRepository: FakeMessageRepository(), composerDraftStore: draftStore)
        await first.loadMessages()
        first.updateComposerText("send me")
        await first.sendPrompt()
        first.flushComposerDraft()
        #expect(first.uiState.composer.text == "")
        first.stopObserving()

        let restored = makeViewModel(messageRepository: FakeMessageRepository(), composerDraftStore: draftStore)
        await restored.loadMessages()
        #expect(restored.uiState.composer.text == "")
        restored.stopObserving()
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

    @Test func loadMessages_initializesAgentFromLatestUserMessage() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .user,
                        text: "Plan this",
                        model: nil,
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        variant: nil,
                        mode: "plan",
                        sentAtEpochMs: 2_000
                    ),
                ]
            )
        )

        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.selectedMode == "plan" }
        #expect(viewModel.uiState.composer.selectedMode == "plan")
        #expect(viewModel.uiState.composer.selectedModeLabel == "Plan")
    }

    @Test func loadMessages_newerUserAgentOverridesOlderCachedAgent() async {
        let selectionStore = InMemorySessionModelSelectionStore()
        await selectionStore.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(
                providerID: "anthropic",
                modelID: "claude-sonnet",
                variant: nil,
                agentID: "build",
                savedAtEpochMs: 1_000
            )
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .user,
                        text: "Switch to plan",
                        model: nil,
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        variant: nil,
                        mode: "plan",
                        sentAtEpochMs: 2_000
                    ),
                ]
            ),
            sessionModelSelectionStore: selectionStore
        )

        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.selectedMode == "plan" }
        #expect(viewModel.uiState.composer.selectedMode == "plan")
    }

    @Test func modelUpdatedEvent_refetchesCatalogThatWasEmptyAfterServerRestart() async {
        let events = FakeForegroundEventStream()
        let models = SequencedModelRepository(catalogs: [ModelSelectionCatalog(options: [], serverDefault: nil)])
        let viewModel = makeViewModel(eventStream: events, modelRepository: models)
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        #expect(viewModel.uiState.composer.modelCatalogError == "No usable models are available. Tap Model to retry.")
        #expect(viewModel.uiState.composer.isModelSelectionReady == false)

        events.emit(OpencodeEvent(type: "provider.updated", sessionID: nil))
        events.emit(OpencodeEvent(type: "model.updated", sessionID: nil))
        _ = await waitUntil { viewModel.uiState.composer.isModelSelectionReady }

        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.modelCatalogError == nil)
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "claude-sonnet")
        #expect(models.calls >= 2)
    }

    @Test func streamReconnect_refetchesCatalogThatWasEmptyAfterServerRestart() async {
        // The restart's catalog events fired while the stream was still down; only the
        // reconnect tells the composer the server is back.
        let events = FakeForegroundEventStream()
        let models = SequencedModelRepository(catalogs: [ModelSelectionCatalog(options: [], serverDefault: nil)])
        let viewModel = makeViewModel(eventStream: events, modelRepository: models)
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        #expect(viewModel.uiState.composer.isModelSelectionReady == false)

        events.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        _ = await waitUntil { viewModel.uiState.composer.isModelSelectionReady }

        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.modelCatalogError == nil)
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "claude-sonnet")
    }

    @Test func emptyCatalog_isRetriedWithBackoffUntilModelsArrive() async {
        let empty = ModelSelectionCatalog(options: [], serverDefault: nil)
        let models = SequencedModelRepository(catalogs: [empty, empty])
        let viewModel = makeViewModel(modelRepository: models, catalogRetryDelaysMs: [10, 10, 10])
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.isModelSelectionReady }

        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.modelCatalogError == nil)
        #expect(models.calls == 3)
    }

    @Test func emptyCatalog_stopsRetryingAfterTheLastBackoffStep() async {
        let empty = ModelSelectionCatalog(options: [], serverDefault: nil)
        let models = SequencedModelRepository(catalogs: [empty, empty, empty, empty])
        let viewModel = makeViewModel(modelRepository: models, catalogRetryDelaysMs: [10, 10])
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        _ = await waitUntil { models.calls >= 3 }
        try? await Task.sleep(for: .milliseconds(100))

        #expect(models.calls == 3)
        #expect(viewModel.uiState.composer.modelCatalogError == "No usable models are available. Tap Model to retry.")
    }

    @Test func reconnectRefreshThatComesBackEmpty_keepsTheLoadedCatalog() async {
        let events = FakeForegroundEventStream()
        let models = SequencedModelRepository(catalogs: [
            FakeModelRepository().catalog,
            ModelSelectionCatalog(options: [], serverDefault: nil),
        ])
        let viewModel = makeViewModel(eventStream: events, modelRepository: models, catalogRetryDelaysMs: [10])
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        #expect(viewModel.uiState.composer.isModelSelectionReady)

        events.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        _ = await waitUntil { models.calls >= 3 }

        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.modelCatalogError == nil)
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "claude-sonnet")
    }

    @Test func unrelatedEvent_doesNotRefetchCatalog() async {
        let events = FakeForegroundEventStream()
        let models = SequencedModelRepository(catalogs: [ModelSelectionCatalog(options: [], serverDefault: nil)])
        let viewModel = makeViewModel(eventStream: events, modelRepository: models)
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        let callsAfterLoad = models.calls
        events.emit(OpencodeEvent(type: "skill.updated", sessionID: nil))
        try? await Task.sleep(for: .milliseconds(100))

        #expect(models.calls == callsAfterLoad)
        #expect(viewModel.uiState.composer.isModelSelectionReady == false)
    }

    @Test func reopenedSession_showsLastSelectionBeforeAnyRepositoryCallResolves() async {
        let (_, pickedOption, catalog) = sharedCatalogFixture()
        let models = GatedModelRepository(catalog: catalog)
        let agents = FakeAgentRepository()
        let connection = Self.makeActiveConnectionManager()
        let store = ModelCatalogStore(modelRepository: models, agentRepository: agents, activeConnectionManager: connection)
        let selectionStore = InMemorySessionModelSelectionStore()
        let first = makeViewModel(
            sessionModelSelectionStore: selectionStore,
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        await first.loadMessages()
        _ = await waitUntil { first.uiState.composer.isModelSelectionReady }
        first.selectModel(pickedOption)
        first.stopObserving()

        models.close()
        let callsBeforeReopen = models.calls
        let second = makeViewModel(
            sessionModelSelectionStore: selectionStore,
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        defer {
            models.open()
            second.stopObserving()
        }

        #expect(second.uiState.composer.isModelSelectionReady)
        #expect(second.uiState.composer.selectedModelLabel == pickedOption.modelLabel)
        #expect(second.uiState.composer.modelOptions == catalog.options)

        // The quiet refresh is still blocked; the chip never falls back to "Loading model…".
        let load = Task { await second.loadMessages() }
        _ = await waitUntil { models.calls > callsBeforeReopen }
        #expect(second.uiState.composer.isModelSelectionReady)
        #expect(second.uiState.composer.selectedModel?.option.modelID == "gpt-4")
        models.open()
        await load.value
        #expect(second.uiState.composer.selectedModel?.option.modelID == "gpt-4")
    }

    @Test func firstOpenedSession_waitsForHistoryInsteadOfShowingTheServerDefault() async {
        let (_, pickedOption, catalog) = sharedCatalogFixture()
        let models = GatedModelRepository(catalog: catalog)
        let (store, connection, agents) = await warmedCatalogStore(models: models)
        let history = AsyncGate()
        let messages = FakeMessageRepository(messages: [
            Message(
                id: "msg-1",
                role: .user,
                text: "Use GPT",
                model: nil,
                modelProviderID: "openai",
                modelID: "gpt-4",
                variant: nil,
                mode: nil,
                sentAtEpochMs: 2_000
            ),
        ])
        messages.listHold = history
        let viewModel = makeViewModel(
            messageRepository: messages,
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        defer { viewModel.stopObserving() }

        // The catalog comes from the store at once; the selection waits for this session's history.
        #expect(viewModel.uiState.composer.modelOptions == catalog.options)
        #expect(viewModel.uiState.composer.isModelSelectionReady == false)
        #expect(viewModel.uiState.composer.selectedModel == nil)
        #expect(viewModel.uiState.composer.selectedModelLabel == "Loading model…")

        let load = Task { await viewModel.loadMessages() }
        _ = await waitUntil { models.calls >= 2 }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(viewModel.uiState.composer.isModelSelectionReady == false)
        #expect(viewModel.uiState.composer.selectedModel == nil)

        await history.open()
        await load.value
        _ = await waitUntil { viewModel.uiState.composer.isModelSelectionReady }

        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.selectedModel?.option == pickedOption)
    }

    @Test func reopenedSession_failedCatalogRefreshKeepsTheShownSelection() async {
        let (defaultOption, _, catalog) = sharedCatalogFixture()
        let models = GatedModelRepository(catalog: catalog)
        let (store, connection, agents) = await warmedCatalogStore(models: models)
        store.recordSelection(
            SessionModelSelection(providerID: "anthropic", modelID: "claude-sonnet", variant: nil, savedAtEpochMs: 1_000),
            worktree: "/repo",
            sessionID: "ses-1"
        )
        models.setFailure(true)

        let viewModel = makeViewModel(
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        defer { viewModel.stopObserving() }
        #expect(viewModel.uiState.composer.isModelSelectionReady)
        await viewModel.loadMessages()

        #expect(models.calls >= 2)
        #expect(viewModel.uiState.composer.isModelSelectionReady)
        #expect(viewModel.uiState.composer.modelCatalogError == nil)
        #expect(viewModel.uiState.composer.selectedModel?.option == defaultOption)
    }

    @Test func reopenedSession_newerUserHistorySwitchesTheProvisionalSelection() async {
        let (defaultOption, pickedOption, catalog) = sharedCatalogFixture()
        let models = GatedModelRepository(catalog: catalog)
        let (store, connection, agents) = await warmedCatalogStore(models: models)
        store.recordSelection(
            SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil, savedAtEpochMs: 1_000),
            worktree: "/repo",
            sessionID: "ses-1"
        )

        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(
                    id: "msg-1",
                    role: .user,
                    text: "Use Claude",
                    model: nil,
                    modelProviderID: "anthropic",
                    modelID: "claude-sonnet",
                    variant: "high",
                    mode: nil,
                    sentAtEpochMs: 2_000
                ),
            ]),
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        defer { viewModel.stopObserving() }
        #expect(viewModel.uiState.composer.selectedModel?.option == pickedOption)

        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.selectedModel?.option == defaultOption }

        #expect(viewModel.uiState.composer.selectedModel?.option == defaultOption)
        #expect(viewModel.uiState.composer.selectedVariant == "high")
        #expect(viewModel.uiState.composer.isModelSelectionReady)
    }

    @Test func reopenedSession_userPickBeforeReconcileIsKept() async {
        let (defaultOption, _, catalog) = sharedCatalogFixture()
        let models = GatedModelRepository(catalog: catalog)
        let (store, connection, agents) = await warmedCatalogStore(models: models)
        store.recordSelection(
            SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil, savedAtEpochMs: 1_000),
            worktree: "/repo",
            sessionID: "ses-1"
        )
        let future = Int64(Date().timeIntervalSince1970 * 1_000) + 3_600_000
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(
                    id: "msg-1",
                    role: .user,
                    text: "Use GPT",
                    model: nil,
                    modelProviderID: "openai",
                    modelID: "gpt-4",
                    variant: nil,
                    mode: nil,
                    sentAtEpochMs: future
                ),
            ]),
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        defer { viewModel.stopObserving() }
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "gpt-4")

        viewModel.selectModel(defaultOption)
        await viewModel.loadMessages()
        try? await Task.sleep(for: .milliseconds(50))

        #expect(viewModel.uiState.composer.selectedModel?.option == defaultOption)
    }

    @Test func reopenedSession_refreshThatDropsTheSelectedModelFallsBackWithNotice() async {
        let (defaultOption, _, catalog) = sharedCatalogFixture()
        let models = GatedModelRepository(catalog: catalog)
        let (store, connection, agents) = await warmedCatalogStore(models: models)
        store.recordSelection(
            SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil, savedAtEpochMs: 1_000),
            worktree: "/repo",
            sessionID: "ses-1"
        )
        models.setCatalog(ModelSelectionCatalog(options: [defaultOption], serverDefault: defaultOption))

        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: []),
            modelRepository: models,
            modelCatalogStore: store,
            activeConnectionManager: connection,
            agentRepository: agents
        )
        defer { viewModel.stopObserving() }
        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "gpt-4")

        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.selectedModel?.option == defaultOption }

        #expect(viewModel.uiState.composer.selectedModel?.option == defaultOption)
        #expect(viewModel.uiState.composer.modelOptions == [defaultOption])
        #expect(viewModel.uiState.composer.modelNotice == "Selected model is unavailable. Using Claude Sonnet.")
        #expect(viewModel.uiState.composer.isModelSelectionReady)
    }

    @Test func selectMode_persistsAgentForTheSessionWithoutNewSessionPreference() async {
        let selectionStore = InMemorySessionModelSelectionStore()
        let viewModel = makeViewModel(sessionModelSelectionStore: selectionStore)
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.composer.isModelSelectionReady }

        viewModel.selectMode("plan")
        let identity = ModelSelectionConnectionIdentity(
            serverBaseURL: "http://127.0.0.1:4096/",
            username: "opencode"
        )
        var storedAgent: String?
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline {
            storedAgent = await selectionStore.loadSession(
                connectionIdentity: identity,
                worktree: "/repo",
                sessionID: "ses-1"
            )?.agentID
            if storedAgent == "plan" { break }
            try? await Task.sleep(for: .milliseconds(10))
        }
        #expect(storedAgent == "plan")
        #expect(
            await selectionStore.loadNewSessionPreference(
                connectionIdentity: identity,
                worktree: "/repo"
            )?.agentID == nil
        )
    }

    @Test func loadMessages_delayedAgentCatalog_restoresCustomAgentFromHistoryWithoutPersistingFallback() async {
        let selectionStore = InMemorySessionModelSelectionStore()
        let agentRepository = FakeAgentRepository(
            selectableAgents: [
                SelectableAgent(id: "build", displayName: "Build"),
                SelectableAgent(id: "review", displayName: "Review"),
            ],
            blockSelectableAgents: true
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .user,
                        text: "Review this",
                        model: nil,
                        modelProviderID: "anthropic",
                        modelID: "claude-sonnet",
                        variant: nil,
                        mode: "review",
                        sentAtEpochMs: 2_000
                    ),
                ]
            ),
            sessionModelSelectionStore: selectionStore,
            agentRepository: agentRepository
        )
        defer { viewModel.stopObserving() }

        await viewModel.loadMessages()
        agentRepository.releaseSelectableAgents()
        _ = await waitUntil { viewModel.uiState.composer.selectedMode == "review" }

        #expect(viewModel.uiState.composer.selectedMode == "review")
        let identity = ModelSelectionConnectionIdentity(
            serverBaseURL: "http://127.0.0.1:4096/",
            username: "opencode"
        )
        #expect(
            await selectionStore.loadSession(
                connectionIdentity: identity,
                worktree: "/repo",
                sessionID: "ses-1"
            )?.agentID == "review"
        )
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

    @Test func loadMessages_serverDefaultVariantIsDefaultWithoutNotice() async {
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
                        variant: "default",
                        mode: nil,
                        sentAtEpochMs: 2_000
                    ),
                ]
            )
        )

        await viewModel.loadMessages()

        #expect(viewModel.uiState.composer.selectedModel?.option.modelID == "claude-sonnet")
        #expect(viewModel.uiState.composer.selectedVariant == nil)
        #expect(viewModel.uiState.composer.modelNotice == nil)
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

    @Test func loadMessages_keepsStoreUserBeforeAssistantWhenUserTimestampIsLater() async {
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(id: "msg-photo", role: .user, text: "What is it", model: nil, mode: nil, sentAtEpochMs: 3_000),
                    Message(id: "msg-asst", role: .assistant, text: "A photo", model: nil, mode: nil, sentAtEpochMs: 1_000),
                    Message(id: "msg-hey", role: .user, text: "hey", model: nil, mode: nil, sentAtEpochMs: 2_000),
                ]
            )
        )
        await viewModel.loadMessages()
        guard viewModel.uiState.timeline == .ready else {
            Issue.record("Expected ready timeline")
            return
        }
        #expect(viewModel.uiState.messages.map(\.text) == ["What is it", "A photo", "hey"])
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
        let loaded = await waitUntil { viewModel.uiState.timeline == .ready }
        #expect(loaded)
        viewModel.updateComposerText("Fail me")
        await viewModel.sendPrompt()
        let ready = await waitUntil {
            viewModel.uiState.timeline == .ready && viewModel.uiState.isSending == false
        }
        #expect(ready)
        let messages = viewModel.uiState.messages
        #expect(viewModel.uiState.isSending == false)
        #expect(!messages.contains { $0.text == "Fail me" })
        #expect(viewModel.uiState.snackbar?.message != nil)
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)
    }

    @Test func sendPrompt_transportAfterBusy_keepsAcceptedPrompt() async {
        let eventStream = FakeForegroundEventStream()
        let repository = FakeMessageRepository(shouldFailSend: true, holdSend: true)
        let viewModel = makeViewModel(messageRepository: repository, eventStream: eventStream)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        let loaded = await waitUntil { viewModel.uiState.timeline == .ready }
        #expect(loaded)
        viewModel.updateComposerText("Accepted prompt")
        let sendTask = Task { await viewModel.sendPrompt() }
        let sending = await waitUntil { viewModel.uiState.isSending }
        #expect(sending)
        #expect(viewModel.uiState.composer.text.isEmpty)

        eventStream.emit(
            OpencodeEvent(
                type: "session.status",
                sessionID: "ses-1",
                rawProperties: ["status": .dictionary(["type": .string("busy")])]
            )
        )
        let busy = await waitUntil { viewModel.uiState.sessionStatusLabel != nil }
        #expect(busy)

        repository.releaseSend()
        await sendTask.value

        #expect(repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.composer.text.isEmpty)
        #expect(viewModel.uiState.isSending == false)
        #expect(viewModel.uiState.snackbar == nil)
        #expect(viewModel.uiState.errorMessage == nil)
        #expect(viewModel.uiState.messages.contains { $0.text == "Accepted prompt" && $0.isPending })
    }

    @Test func sendPrompt_transportWhileIdle_restoresComposer() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(shouldFailSend: true))
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        let loaded = await waitUntil { viewModel.uiState.timeline == .ready }
        #expect(loaded)
        viewModel.updateComposerText("Lost prompt")
        await viewModel.sendPrompt()
        let ready = await waitUntil { viewModel.uiState.isSending == false }
        #expect(ready)
        #expect(viewModel.uiState.composer.text == "Lost prompt")
        #expect(!viewModel.uiState.messages.contains { $0.text == "Lost prompt" })
        #expect(viewModel.uiState.snackbar?.message != nil)
        #expect(viewModel.uiState.snackbar?.tone == .error)
        #expect(viewModel.uiState.errorMessage == nil)
        #expect(viewModel.uiState.isSending == false)
    }

    @Test func sendPrompt_postSendReloadFailure_keepsOptimisticMessageWithoutError() async {
        let repository = FakeMessageRepository(failListMessagesAfterSend: true)
        let viewModel = makeViewModel(messageRepository: repository)
        await viewModel.loadMessages()
        let loaded = await waitUntil { viewModel.uiState.timeline == .ready }
        #expect(loaded)
        viewModel.updateComposerText("Pending prompt")
        await viewModel.sendPrompt()
        let ready = await waitUntil {
            viewModel.uiState.timeline == .ready
                && viewModel.uiState.isSending == false
                && viewModel.uiState.messages.contains { $0.text == "Pending prompt" && $0.isPending }
        }
        #expect(ready)
        let messages = viewModel.uiState.messages
        #expect(repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.isSending == false)
        #expect(messages.contains { $0.text == "Pending prompt" && $0.isPending })
        #expect(viewModel.uiState.errorMessage == nil)
    }

    @Test func catchUpChrome_showsSyncingPill() async {
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(eventStream: eventStream)
        let ready = await waitUntil { viewModel.uiState.timeline == .ready }
        #expect(ready)
        #expect(viewModel.uiState.syncingIndicatorLabel == nil)

        eventStream.start()
        eventStream.setConnectionState(.disconnected)
        let shown = await waitUntil {
            viewModel.uiState.syncingIndicatorLabel == SessionMessageSyncChrome.statusLabel
        }
        #expect(shown)
        viewModel.stopObserving()
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
        eventStream.openGap()
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
        eventStream.openGap()
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
        #expect(SessionConnectionStatus(label: .live) == .live)
        #expect(SessionConnectionStatus(label: .connecting) == .connecting)
        #expect(SessionConnectionStatus(label: .offline) == .offline)
        #expect(SessionConnectionStatus(label: .authFailed) == .authFailed)
        #expect(SessionConnectionStatus.authFailed.openCodeStatus == .authFailed)
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

    @Test func runClientSlashAction_files_opensFilesAtWorktreeAndDismissesCommands() async {
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository())
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        viewModel.openCommands()

        viewModel.runClientSlashAction(.files)

        #expect(viewModel.uiState.pendingFilesOpen == PendingFilesOpen(worktree: "/repo"))
        #expect(viewModel.uiState.commandsSheetVisible == false)
        viewModel.consumePendingFilesOpen()
        #expect(viewModel.uiState.pendingFilesOpen == nil)
    }

    @Test func clientSlashActions_visibility_reflectsCatalogGates() async {
        let emptyMessages = makeViewModel(messageRepository: FakeMessageRepository(messages: []))
        defer { emptyMessages.stopObserving() }
        await emptyMessages.loadMessages()
        #expect(emptyMessages.uiState.visibleClientSlashActions == [.new, .terminal, .files, .mcp])

        let withMessages = makeViewModel(messageRepository: FakeMessageRepository())
        defer { withMessages.stopObserving() }
        await withMessages.loadMessages()
        _ = await waitUntil { withMessages.uiState.visibleClientSlashActions.contains(.compact) }
        let visible = withMessages.uiState.visibleClientSlashActions
        #expect(visible.contains(.new))
        #expect(visible.contains(.terminal))
        #expect(visible.contains(.fork))
        #expect(visible.contains(.undo))
        #expect(visible.contains(.compact))
        #expect(visible.contains(.redo) == false)
    }

    @Test func runClientSlashAction_new_requestsOptimisticReplaceKeepsDraftNeverRunsCommand() async {
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

        #expect(sessionRepository.createSessionCallCount == 0)
        #expect(viewModel.uiState.commandsSheetVisible == false)
        #expect(viewModel.uiState.pendingSessionReplace?.sessionID == OptimisticSessionCreate.placeholderSessionID)
        #expect(viewModel.uiState.pendingSessionReplace?.sessionTitle == "New Session")
        #expect(viewModel.uiState.pendingSessionReplace?.worktree == "/repo")
        #expect(viewModel.uiState.pendingSessionReplace?.optimisticNavigationInstanceID != nil)
        #expect(viewModel.uiState.pendingSessionReplace?.sessionListItem.parentID == nil)
        #expect(viewModel.uiState.composer.text == "draft that should survive")
        #expect(commandRepository.runCommandCalled == false)

        viewModel.consumePendingSessionReplace()
        #expect(viewModel.uiState.pendingSessionReplace == nil)
    }

    @Test func startObservingOptimisticCreate_promotesSessionAndLoadsMessages() async {
        let sessionRepository = FakeChatSessionRepository(
            createSessionResult: Session(
                id: "ses-new", title: "Created", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository,
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            sessionTitle: "New Session"
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        #expect(viewModel.uiState.sessionID == "ses-new")
        #expect(viewModel.uiState.timeline == .empty || viewModel.uiState.timeline == .ready)
        #expect(sessionRepository.createSessionCallCount == 1)
    }

    @Test func startObservingOptimisticCreate_failureStaysOnChatWithError() async {
        let repository = FakeMessageRepository()
        let sessionRepository = FakeChatSessionRepository(
            createSessionError: .http(statusCode: 500, message: "boom")
        )
        let viewModel = makeViewModel(
            messageRepository: repository,
            sessionRepository: sessionRepository,
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            sessionTitle: "New Session"
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        #expect(viewModel.uiState.shouldLeaveSession == false)
        #expect(viewModel.uiState.timeline == .error)
        #expect(viewModel.uiState.errorMessage == "boom")
        #expect(viewModel.uiState.sessionID == OptimisticSessionCreate.placeholderSessionID)

        viewModel.updateComposerText("hi")
        await viewModel.sendPrompt()
        viewModel.archiveSession()
        viewModel.deleteSession()
        #expect(repository.sendPromptAsyncCalled == false)
        #expect(sessionRepository.archiveCallCount == 0)
        #expect(sessionRepository.deleteCallCount == 0)
    }

    @Test func startObservingOptimisticCreate_transportFailureShowsReachabilityError() async {
        let sessionRepository = FakeChatSessionRepository(
            createSessionError: .transport("offline")
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository,
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            sessionTitle: "New Session"
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        #expect(viewModel.uiState.shouldLeaveSession == false)
        #expect(viewModel.uiState.timeline == .error)
        #expect(viewModel.uiState.errorMessage == "Unable to reach the server")
    }

    @Test func startObservingAgainWhileCreatingOrAfterPromotion_issuesSingleCreate() async {
        let sessionRepository = FakeChatSessionRepository(
            createSessionResult: Session(
                id: "ses-new", title: "Created", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository,
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            sessionTitle: "New Session"
        )
        defer { viewModel.stopObserving() }

        viewModel.startObserving()
        viewModel.startObserving()
        await viewModel.loadMessages()
        #expect(viewModel.uiState.sessionID == "ses-new")
        #expect(sessionRepository.createSessionCallCount == 1)

        viewModel.startObserving()
        await viewModel.loadMessages()
        #expect(viewModel.uiState.sessionID == "ses-new")
        #expect(sessionRepository.createSessionCallCount == 1)
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
        #expect(viewModel.uiState.forkDraftTitle == "Fork of Title")
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
        #expect(viewModel.uiState.pendingSessionReplace == ChildSessionNavigationTarget(
            sessionID: "ses-3",
            sessionTitle: "Forked copy",
            worktree: "/repo",
            optimisticNavigationInstanceID: nil
        ))
        // A fork is a top-level session: opening it must not lock the composer as a child.
        let opened = viewModel.uiState.pendingSessionReplace?.sessionListItem
        #expect(opened?.id == "ses-3")
        #expect(opened?.parentID == nil)
        #expect(SessionChatComposerKind.resolve(
            isChildSession: opened?.parentID != nil,
            hasPermission: false,
            hasQuestion: false
        ) == .prompt)
    }

    @Test func childSessionTarget_opensAsAChildOfItsParent() {
        let target = ChildSessionNavigationTarget(
            sessionID: "ses-child",
            sessionTitle: "Explore",
            worktree: "/repo",
            optimisticNavigationInstanceID: nil,
            parentID: "ses-1"
        )
        #expect(target.sessionListItem.parentID == "ses-1")
        #expect(SessionChatComposerKind.resolve(
            isChildSession: target.sessionListItem.parentID != nil,
            hasPermission: false,
            hasQuestion: false
        ) == .childLockout)
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

    @Test func openForkDialog_fromOverflow_forksAtTipWithFallbackTitlePrefill() async {
        let sessionRepository = FakeChatSessionRepository()
        let draftStore = InMemoryComposerDraftStore()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            composerDraftStore: draftStore,
            sessionRepository: sessionRepository,
            sessionTitle: "   "
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.openForkDialog()
        #expect(viewModel.uiState.forkDialogVisible)
        #expect(viewModel.uiState.forkDraftTitle == "Forked session")

        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkResultVisible }

        #expect(sessionRepository.forkSessionRequest == .init(id: "ses-1", atMessageID: nil))
        #expect(sessionRepository.updateRequest?.title == "Forked session")
        // Tip forks start with an empty composer.
        #expect(draftStore.load(
            connectionIdentity: Self.testConnectionIdentity,
            worktree: "/repo",
            sessionID: "forked-ses"
        ) == nil)
    }

    @Test func openForkDialog_whileOffline_opensButConfirmWaitsForLiveConnection() async {
        let sessionRepository = FakeChatSessionRepository()
        let liveUpdates = FixedLiveUpdates(
            chrome: LiveUpdatesChrome(label: .offline, canMutate: false, composerEnabled: true)
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository,
            liveUpdates: liveUpdates
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        // Opening is not gated on the connection; the dialog's Fork button follows canMutate.
        viewModel.openForkDialog(atMessageID: "msg-3")
        #expect(viewModel.uiState.forkDialogVisible)
        #expect(viewModel.uiState.canMutate == false)

        viewModel.confirmFork()
        await Task.yield()
        #expect(sessionRepository.forkSessionRequest == nil)
        #expect(viewModel.uiState.forkDialogVisible)
        #expect(viewModel.uiState.forkSubmitting == false)

        liveUpdates.set(LiveUpdatesChrome(label: .live, canMutate: true, composerEnabled: true))
        await waitUntil { viewModel.uiState.canMutate }
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkResultVisible }
        #expect(sessionRepository.forkSessionRequest == .init(id: "ses-1", atMessageID: "msg-3"))
    }

    @Test func forkTitlePrefill_trimsTitleAndFallsBackWhenBlank() {
        #expect(SessionChatCommands.forkTitlePrefill(currentTitle: "  Fix login  ") == "Fork of Fix login")
        #expect(SessionChatCommands.forkTitlePrefill(currentTitle: "") == "Forked session")
        #expect(SessionChatCommands.forkTitlePrefill(currentTitle: " \n ") == "Forked session")
    }

    @Test func openForkDialog_fromMessage_forksBeforeMessageAndPrefillsForkComposer() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
            Message(id: "msg-3", role: .user, text: "Try the other approach", model: nil, mode: nil, sentAtEpochMs: 3),
        ]
        let sessionRepository = FakeChatSessionRepository(
            forkSessionResult: Session(
                id: "ses-fork", title: "Title (fork #1)", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let draftStore = InMemoryComposerDraftStore()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            composerDraftStore: draftStore,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.openForkDialog(atMessageID: "msg-3")
        #expect(viewModel.uiState.forkDraftTitle == "Fork of Title")
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkResultVisible }

        #expect(sessionRepository.forkSessionRequest == .init(id: "ses-1", atMessageID: "msg-3"))
        #expect(sessionRepository.updateRequest == .init(id: "ses-fork", title: "Fork of Title", directory: "/repo"))
        #expect(draftStore.load(
            connectionIdentity: Self.testConnectionIdentity,
            worktree: "/repo",
            sessionID: "ses-fork"
        )?.text == "Try the other approach")
        // The source session's own composer is untouched.
        #expect(viewModel.uiState.composer.text == "")

        // Opening the fork restores the prefilled prompt in its composer.
        let fork = makeViewModel(
            messageRepository: FakeMessageRepository(),
            composerDraftStore: draftStore,
            sessionID: "ses-fork"
        )
        defer { fork.stopObserving() }
        await fork.loadMessages()
        await waitUntil { fork.uiState.composer.text == "Try the other approach" }
        #expect(fork.uiState.composer.text == "Try the other approach")
    }

    @Test func confirmFork_renameFails_keepsForkShowsPromptAndNonBlockingError() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "Start here", model: nil, mode: nil, sentAtEpochMs: 1),
        ]
        let sessionRepository = FakeChatSessionRepository(
            updateSessionError: .http(statusCode: 500, message: "rename boom"),
            forkSessionResult: Session(
                id: "ses-fork", title: "Title (fork #1)", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: nil
            )
        )
        let draftStore = InMemoryComposerDraftStore()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            composerDraftStore: draftStore,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.openForkDialog(atMessageID: "msg-1")
        viewModel.updateForkDraft("My fork")
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkResultVisible }

        #expect(viewModel.uiState.forkDialogVisible == false)
        #expect(viewModel.uiState.forkSubmitting == false)
        #expect(viewModel.uiState.forkErrorMessage == nil)
        #expect(viewModel.uiState.forkedSessionTitle == "Title (fork #1)")
        #expect(viewModel.uiState.snackbar?.message == "Fork created, but it couldn't be renamed.")
        #expect(viewModel.uiState.snackbar?.tone == .error)
        // The prefill still lands even though the rename failed.
        #expect(draftStore.load(
            connectionIdentity: Self.testConnectionIdentity,
            worktree: "/repo",
            sessionID: "ses-fork"
        )?.text == "Start here")

        // A second confirm for the same flow must not create another fork.
        viewModel.confirmFork()
        await Task.yield()
        #expect(sessionRepository.forkSessionCallCount == 1)
        #expect(sessionRepository.updateSessionCallCount == 1)

        viewModel.chooseForkOpen()
        #expect(viewModel.uiState.pendingSessionReplace == ChildSessionNavigationTarget(
            sessionID: "ses-fork",
            sessionTitle: "Title (fork #1)",
            worktree: "/repo",
            optimisticNavigationInstanceID: nil
        ))
    }

    @Test func openForkDialog_fromMessage_forkFailureKeepsDialogOpenWithoutPrefill() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "Start here", model: nil, mode: nil, sentAtEpochMs: 1),
        ]
        let sessionRepository = FakeChatSessionRepository(forkSessionError: .http(statusCode: 500, message: "boom"))
        let draftStore = InMemoryComposerDraftStore()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            composerDraftStore: draftStore,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.openForkDialog(atMessageID: "msg-1")
        viewModel.confirmFork()
        await waitUntil { viewModel.uiState.forkErrorMessage != nil }

        #expect(viewModel.uiState.forkDialogVisible)
        #expect(viewModel.uiState.forkSubmitting == false)
        #expect(viewModel.uiState.forkResultVisible == false)
        #expect(sessionRepository.forkSessionRequest == .init(id: "ses-1", atMessageID: "msg-1"))
        #expect(sessionRepository.updateSessionCallCount == 0)
        #expect(draftStore.load(
            connectionIdentity: Self.testConnectionIdentity,
            worktree: "/repo",
            sessionID: "forked-ses"
        ) == nil)
    }

    @Test func forkDialog_cancelOrBlankTitle_makesNoRepositoryCalls() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(messageRepository: FakeMessageRepository(), sessionRepository: sessionRepository)
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.openForkDialog()
        viewModel.updateForkDraft("   ")
        viewModel.confirmFork()
        #expect(viewModel.uiState.forkErrorMessage == "Enter a session title")
        #expect(viewModel.uiState.forkDialogVisible)

        viewModel.cancelForkDialog()
        #expect(viewModel.uiState.forkDialogVisible == false)
        // Confirming after cancel is a no-op too.
        viewModel.confirmFork()
        await Task.yield()

        #expect(sessionRepository.forkSessionCallCount == 0)
        #expect(sessionRepository.updateSessionCallCount == 0)
        #expect(viewModel.uiState.forkResultVisible == false)
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
        #expect(viewModel.uiState.hasPendingRevert)
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

    @Test func revertMessage_revertsThatUserTurnAndRestoresComposer() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
            Message(id: "msg-3", role: .user, text: "Second", model: nil, mode: nil, sentAtEpochMs: 3),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.composer.text == "First" }

        #expect(sessionRepository.revertRequest == .init(id: "ses-1", messageID: "msg-1", partID: nil))
        #expect(sessionRepository.abortCallCount == 0)
        #expect(viewModel.uiState.hasPendingRevert)
    }

    @Test func revertStagedElsewhere_hidesRevertedTurnsUntilCleared() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
            Message(id: "msg-3", role: .user, text: "Second", model: nil, mode: nil, sentAtEpochMs: 3),
            Message(id: "msg-4", role: .assistant, text: "Reply 2", model: nil, mode: nil, sentAtEpochMs: 4),
        ]
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            eventStream: eventStream,
            serverProtocol: .v2
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        func shows(_ id: String) -> Bool { viewModel.uiState.messages.contains { $0.id.contains(id) } }
        #expect(shows("msg-3"))

        eventStream.emit(
            OpencodeEvent(
                type: "session.revert.staged",
                sessionID: "ses-1",
                rawProperties: ["revert": .dictionary(["messageID": .string("msg-3")])]
            )
        )
        let hidden = await waitUntil { !shows("msg-3") && !shows("msg-4") }
        #expect(hidden)
        #expect(shows("msg-1"))
        #expect(shows("msg-2"))
        #expect(viewModel.uiState.hasPendingRevert)

        // A v2 rename (projected as a partial session.updated) keeps the staged revert.
        eventStream.emit(
            OpencodeEvent(
                type: "session.updated",
                sessionID: "ses-1",
                rawProperties: [
                    "info": .dictionary([
                        "id": .string("ses-1"),
                        "sessionID": .string("ses-1"),
                        "title": .string("Renamed"),
                    ]),
                ]
            )
        )
        let renamed = await waitUntil { viewModel.uiState.sessionTitle == "Renamed" }
        #expect(renamed)
        #expect(!shows("msg-3"))
        #expect(viewModel.uiState.hasPendingRevert)

        eventStream.emit(OpencodeEvent(type: "session.revert.cleared", sessionID: "ses-1"))
        let restored = await waitUntil { shows("msg-3") && shows("msg-4") }
        #expect(restored)
        #expect(!viewModel.uiState.hasPendingRevert)
    }

    @Test func v1SessionUpdatedRevertFromElsewhere_showsPendingRevert() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            eventStream: eventStream
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        eventStream.emit(
            OpencodeEvent(
                type: "session.updated",
                sessionID: nil,
                rawProperties: [
                    "info": .dictionary([
                        "id": .string("ses-1"),
                        "title": .string("Title"),
                        "time": .dictionary(["created": .int(1)]),
                        "revert": .dictionary(["messageID": .string("msg-2")]),
                    ]),
                ]
            )
        )
        let pending = await waitUntil { viewModel.uiState.hasPendingRevert }
        #expect(pending)
        #expect(!viewModel.uiState.messages.contains { $0.id.contains("msg-2") })
    }

    @Test func cancelRevert_unrevertsAndClearsComposer() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }
        #expect(viewModel.uiState.composer.text == "First")

        viewModel.cancelRevert()
        await waitUntil { sessionRepository.unrevertRequest != nil }

        #expect(sessionRepository.unrevertRequest == "ses-1")
        #expect(viewModel.uiState.composer.text == "")
        #expect(!viewModel.uiState.hasPendingRevert)
    }

    @Test func cancelRevert_withoutPendingRevert_isNoop() async {
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.cancelRevert()
        try? await Task.sleep(nanoseconds: 50_000_000)

        #expect(sessionRepository.unrevertRequest == nil)
    }

    @Test func sendPrompt_afterRevert_clearsPendingRevertChip() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let repository = FakeMessageRepository(messages: messages)
        let viewModel = makeViewModel(
            messageRepository: repository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }

        viewModel.updateComposerText("Changed")
        await viewModel.sendPrompt()

        #expect(repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.composer.text == "")
        #expect(!viewModel.uiState.hasPendingRevert)
        #expect(sessionRepository.unrevertRequest == nil)
    }

    @Test func sendPrompt_withPendingRevert_commitsRevertBeforePrompt() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let repository = FakeMessageRepository(messages: messages)
        var promptSentBeforeCommit: Bool?
        sessionRepository.onCommitRevert = { promptSentBeforeCommit = repository.sendPromptAsyncCalled }
        let viewModel = makeViewModel(
            messageRepository: repository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }

        viewModel.updateComposerText("Changed")
        await viewModel.sendPrompt()

        #expect(sessionRepository.commitRevertRequests == ["ses-1"])
        #expect(promptSentBeforeCommit == false)
        #expect(repository.sendPromptAsyncCalled)
        #expect(!viewModel.uiState.hasPendingRevert)
    }

    @Test func sendPrompt_withoutPendingRevert_doesNotCommit() async {
        let sessionRepository = FakeChatSessionRepository()
        let repository = FakeMessageRepository()
        let viewModel = makeViewModel(
            messageRepository: repository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.updateComposerText("Hello")
        await viewModel.sendPrompt()

        #expect(repository.sendPromptAsyncCalled)
        #expect(sessionRepository.commitRevertRequests.isEmpty)
    }

    @Test func sendPrompt_revertCommitFailure_keepsPromptUnsentAndReportsError() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository(
            commitRevertError: .http(statusCode: 409, message: "Session is busy")
        )
        let repository = FakeMessageRepository(messages: messages)
        let viewModel = makeViewModel(
            messageRepository: repository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }

        viewModel.updateComposerText("Changed")
        await viewModel.sendPrompt()

        #expect(sessionRepository.commitRevertRequests == ["ses-1"])
        #expect(!repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.composer.text == "Changed")
        #expect(viewModel.uiState.snackbar?.message == "Session is busy")
        #expect(viewModel.uiState.hasPendingRevert)
    }

    @Test func sendPrompt_failure_keepsPendingRevertChip() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let repository = FakeMessageRepository(messages: messages, shouldFailSend: true)
        let viewModel = makeViewModel(
            messageRepository: repository,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }

        viewModel.updateComposerText("Changed")
        await viewModel.sendPrompt()

        #expect(repository.sendPromptAsyncCalled)
        #expect(viewModel.uiState.hasPendingRevert)
        #expect(sessionRepository.unrevertRequest == nil)
    }

    @Test func revertMessage_whenBusy_abortsBeforeReverting() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .user, text: "Second", model: nil, mode: nil, sentAtEpochMs: 2),
        ]
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        #expect(viewModel.uiState.sessionStatusLabel != nil)

        viewModel.revertMessage("msg-1")
        await waitUntil { sessionRepository.revertRequest != nil }

        #expect(sessionRepository.abortCallCount == 1)
        #expect(sessionRepository.revertRequest?.messageID == "msg-1")
        await waitUntil { viewModel.uiState.composer.text == "First" }
    }

    @Test func revertMessage_unknownMessage_isNoop() async {
        let messages = [Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1)]
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("missing")
        try? await Task.sleep(for: .milliseconds(50))

        #expect(sessionRepository.revertRequest == nil)
        #expect(viewModel.uiState.composer.text == "")
    }

    @Test func revertMessage_attachmentOnly_revertsThatTurn() async {
        let file = FilePart(
            id: "file-1",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGk="
        )
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .user,
                        text: "",
                        parts: [.file(file)],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: 1
                    ),
                ]
            ),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { sessionRepository.revertRequest != nil }

        #expect(sessionRepository.revertRequest?.messageID == "msg-1")
        #expect(viewModel.uiState.composer.text == "")
        #expect(viewModel.uiState.hasPendingRevert)
    }

    @Test func revertMessage_afterEarlierRevert_ignoresLaterStillVisibleTurn() async {
        let messages = [
            Message(id: "msg-1", role: .user, text: "First", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(id: "msg-2", role: .assistant, text: "Reply", model: nil, mode: nil, sentAtEpochMs: 2),
            Message(id: "msg-3", role: .user, text: "Second", model: nil, mode: nil, sentAtEpochMs: 3),
        ]
        let sessionRepository = FakeChatSessionRepository()
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: messages),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.revertMessage("msg-1")
        await waitUntil { viewModel.uiState.hasPendingRevert }
        #expect(sessionRepository.revertRequest?.messageID == "msg-1")

        viewModel.revertMessage("msg-3")
        try? await Task.sleep(for: .milliseconds(50))

        #expect(sessionRepository.revertRequest?.messageID == "msg-1")
        #expect(viewModel.uiState.composer.text == "First")
    }

    @Test func abortSession_interruptsBoundSessionAndRunningSubagentChild() async {
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "",
                        parts: [
                            .tool(
                                ToolPart(
                                    id: "prt-1",
                                    toolName: "subagent",
                                    status: .running,
                                    input: [
                                        "agent": .string("explore"),
                                        "description": .string("Find git projects"),
                                    ],
                                    metadata: ["sessionID": .string("ses-child")]
                                )
                            ),
                        ],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ]
            ),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil {
            viewModel.uiState.messages.contains { row in
                row.blocks.contains { block in
                    if case .childSessionRow(_, let child) = block {
                        return child.childSessionId == "ses-child"
                    }
                    return false
                }
            }
        }

        await viewModel.abortSession()

        #expect(Set(sessionRepository.abortSessionIDs) == ["ses-1", "ses-child"])
    }

    @Test func runningSubagentLoadedWithoutChildID_linksChildSessionByDescription() async {
        let previousDelay = SessionChatTimeline.childSessionLookupDelay
        SessionChatTimeline.childSessionLookupDelay = .zero
        defer { SessionChatTimeline.childSessionLookupDelay = previousDelay }
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        sessionRepository.childSessions = [
            Session(
                id: "ses-child",
                title: "Find git projects",
                slug: nil,
                projectID: nil,
                directory: nil,
                parentID: "ses-1",
                createdAtEpochMs: 1,
                updatedAtEpochMs: nil
            ),
        ]
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "",
                        parts: [
                            .tool(
                                ToolPart(
                                    id: "prt-1",
                                    toolName: "subagent",
                                    status: .running,
                                    input: [
                                        "agent": .string("general"),
                                        "description": .string("Find git projects"),
                                    ],
                                    metadata: [:]
                                )
                            ),
                        ],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ]
            ),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        let linked = await waitUntil {
            viewModel.uiState.messages.contains { row in
                row.blocks.contains { block in
                    if case .childSessionRow(_, let child) = block {
                        return child.childSessionId == "ses-child"
                    }
                    return false
                }
            }
        }

        #expect(linked)
        #expect(sessionRepository.childSessionRequests == ["ses-1"])
    }

    @Test func childSessionLookupFailure_retriesAndLinks() async {
        let previousDelay = SessionChatTimeline.childSessionLookupDelay
        SessionChatTimeline.childSessionLookupDelay = .zero
        defer { SessionChatTimeline.childSessionLookupDelay = previousDelay }
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        sessionRepository.childSessionFailuresRemaining = 1
        sessionRepository.childSessions = [
            Session(
                id: "ses-child",
                title: "Find git projects",
                slug: nil,
                projectID: nil,
                directory: nil,
                parentID: "ses-1",
                createdAtEpochMs: 1,
                updatedAtEpochMs: nil
            ),
        ]
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "",
                        parts: [
                            .tool(
                                ToolPart(
                                    id: "prt-1",
                                    toolName: "subagent",
                                    status: .running,
                                    input: [
                                        "agent": .string("general"),
                                        "description": .string("Find git projects"),
                                    ],
                                    metadata: [:]
                                )
                            ),
                        ],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ]
            ),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        let linked = await waitUntil {
            viewModel.uiState.messages.contains { row in
                row.blocks.contains { block in
                    if case .childSessionRow(_, let child) = block {
                        return child.childSessionId == "ses-child"
                    }
                    return false
                }
            }
        }

        #expect(linked)
        #expect(sessionRepository.childSessionRequests == ["ses-1", "ses-1"])
    }

    @Test func abortSession_parentSuccessChildFailure_clearsWorking() async {
        let sessionRepository = FakeChatSessionRepository(
            sessionStatusType: "busy",
            abortErrorsByID: ["ses-child": .http(statusCode: 500, message: "child abort failed")]
        )
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(
                messages: [
                    Message(
                        id: "msg-1",
                        role: .assistant,
                        text: "",
                        parts: [
                            .tool(
                                ToolPart(
                                    id: "prt-1",
                                    toolName: "subagent",
                                    status: .running,
                                    input: [
                                        "agent": .string("explore"),
                                        "description": .string("Find git projects"),
                                    ],
                                    metadata: ["sessionID": .string("ses-child")]
                                )
                            ),
                        ],
                        model: nil,
                        mode: nil,
                        sentAtEpochMs: nil
                    ),
                ]
            ),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.sessionStatusLabel == "Working" }
        #expect(viewModel.uiState.workingIndicatorLabel == "Working")

        await viewModel.abortSession()

        #expect(Set(sessionRepository.abortSessionIDs) == ["ses-1", "ses-child"])
        await waitUntil { viewModel.uiState.sessionStatusLabel == nil }
        #expect(viewModel.uiState.snackbar == nil)
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
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }
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

    @Test func runClientSlashAction_compactEnqueued_staysCompactingUntilCompactionCompletes() async {
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sessionRepository = FakeChatSessionRepository(compactStart: .enqueued)
        let viewModel = makeViewModel(
            messageRepository: repository,
            eventStream: eventStream,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        try? await Task.sleep(for: .milliseconds(100))

        // POST /compact returned (enqueued): no success yet, still Compacting….
        #expect(viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel)
        #expect(viewModel.uiState.snackbar == nil)

        repository.messages.append(
            Message(
                id: "msg-c",
                role: .assistant,
                text: "",
                parts: [.compaction(CompactionPart(id: "msg-c", completed: true, summary: "## Goal\nShip it"))],
                model: nil,
                mode: "compaction",
                sentAtEpochMs: 5
            )
        )
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }

        #expect(viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess)
        #expect(viewModel.uiState.sessionStatusLabel == nil)
    }

    @Test func runClientSlashAction_compactEnqueued_failedCompactionShowsNoSuccess() async {
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sessionRepository = FakeChatSessionRepository(compactStart: .enqueued)
        let viewModel = makeViewModel(
            messageRepository: repository,
            eventStream: eventStream,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        repository.messages.append(
            Message(
                id: "msg-c",
                role: .assistant,
                text: "",
                parts: [.compaction(CompactionPart(id: "msg-c", completed: true))],
                model: nil,
                mode: "compaction",
                sentAtEpochMs: 5
            )
        )
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.sessionStatusLabel == nil }
        try? await Task.sleep(for: .milliseconds(100))

        #expect(viewModel.uiState.sessionStatusLabel == nil)
        #expect(viewModel.uiState.snackbar == nil)
    }

    @Test func runClientSlashAction_compactEnqueued_timelineCompletesBeforePostReturns_showsOneSuccess() async {
        let hold = AsyncGate()
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sessionRepository = FakeChatSessionRepository(summarizeHold: hold, compactStart: .enqueued)
        let viewModel = makeViewModel(
            messageRepository: repository,
            eventStream: eventStream,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        #expect(viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel)

        // The live compaction item completes while POST /compact is still in flight.
        repository.messages.append(completedCompactionMessage(summary: "## Goal\nShip it"))
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.sessionStatusLabel == nil }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(viewModel.uiState.sessionStatusLabel == nil)
        #expect(viewModel.uiState.snackbar == nil)

        await hold.open()
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }
        #expect(viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess)
        #expect(viewModel.uiState.sessionStatusLabel == nil)

        viewModel.consumeSnackbar()
        try? await Task.sleep(for: .milliseconds(150))
        #expect(viewModel.uiState.snackbar == nil)
    }

    @Test func runClientSlashAction_compactEnqueued_busyThenIdleBeforePostReturns_clearsWithoutSuccess() async {
        let hold = AsyncGate()
        let eventStream = FakeForegroundEventStream()
        let sessionRepository = FakeChatSessionRepository(summarizeHold: hold, compactStart: .enqueued)
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            eventStream: eventStream,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        eventStream.emit(busyStatusEvent())
        try? await Task.sleep(for: .milliseconds(100))
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(100))
        // Enqueue not confirmed yet: idle alone does not end the compaction.
        #expect(viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel)

        await hold.open()
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.sessionStatusLabel == nil }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(viewModel.uiState.sessionStatusLabel == nil)
        #expect(viewModel.uiState.snackbar == nil)
    }

    @Test func runClientSlashAction_compactFinished_timelineCompletesFirst_showsOneSuccess() async {
        let hold = AsyncGate()
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sessionRepository = FakeChatSessionRepository(summarizeHold: hold, compactStart: .finished)
        let viewModel = makeViewModel(
            messageRepository: repository,
            eventStream: eventStream,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        repository.messages.append(completedCompactionMessage(summary: "## Goal\nShip it"))
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.sessionStatusLabel == nil }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(viewModel.uiState.snackbar == nil)

        await hold.open()
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }
        #expect(viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess)

        viewModel.consumeSnackbar()
        try? await Task.sleep(for: .milliseconds(150))
        #expect(viewModel.uiState.snackbar == nil)
        #expect(viewModel.uiState.sessionStatusLabel == nil)
    }

    @Test func runClientSlashAction_compactEnqueued_stillBusyWhenPostReturns_staysCompacting() async {
        let hold = AsyncGate()
        let repository = FakeMessageRepository()
        let eventStream = FakeForegroundEventStream()
        // Busy on the server too, so the reconnect refresh below keeps the session busy.
        let sessionRepository = FakeChatSessionRepository(
            sessionStatusType: "busy",
            summarizeHold: hold,
            compactStart: .enqueued
        )
        let viewModel = makeViewModel(
            messageRepository: repository,
            eventStream: eventStream,
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        _ = await waitUntil { viewModel.uiState.visibleClientSlashActions.contains(.compact) }

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        eventStream.emit(busyStatusEvent())
        try? await Task.sleep(for: .milliseconds(100))

        // The post-enqueue re-check must see the session still busy, not treat it as idle.
        await hold.open()
        try? await Task.sleep(for: .milliseconds(150))
        #expect(viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel)
        #expect(viewModel.uiState.snackbar == nil)

        repository.messages.append(completedCompactionMessage(summary: "## Goal\nShip it"))
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await waitUntil(timeoutMs: 5_000) { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }
        #expect(viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess)
    }

    private func completedCompactionMessage(summary: String?) -> Message {
        Message(
            id: "msg-c",
            role: .assistant,
            text: "",
            parts: [.compaction(CompactionPart(id: "msg-c", completed: true, summary: summary))],
            model: nil,
            mode: "compaction",
            sentAtEpochMs: 5
        )
    }

    private func busyStatusEvent() -> OpencodeEvent {
        OpencodeEvent(
            type: "session.status",
            sessionID: "ses-1",
            rawProperties: ["status": .dictionary(["type": .string("busy")])]
        )
    }

    @Test func runClientSlashAction_compact_showsCompactingWhileInFlight() async {
        let hold = AsyncGate()
        let clock = ChatTestSyncClock(now: 1_000)
        let sessionRepository = FakeChatSessionRepository(summarizeHold: hold)
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(),
            sessionRepository: sessionRepository,
            clock: clock
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()

        viewModel.runClientSlashAction(.compact)
        await waitUntil { sessionRepository.summarizeRequest != nil }
        await waitUntil { viewModel.uiState.sessionStatusLabel == SessionCompactChrome.statusLabel }

        #expect(viewModel.uiState.workingIndicatorLabel == SessionCompactChrome.statusLabel)

        await hold.open()
        await waitUntil { viewModel.uiState.snackbar?.message == SessionCompactChrome.fallbackSuccess }
        await waitUntil { viewModel.uiState.workingIndicatorLabel == WorkingTurnIndicator.idleLabel }
        #expect(viewModel.uiState.workingIndicatorCollapsed == false)
        try? await clock.awaitWaiter(milliseconds: Int(WorkingTurnIndicator.idleHoldMilliseconds))
        clock.now += WorkingTurnIndicator.idleHoldMilliseconds
        clock.release(milliseconds: Int(WorkingTurnIndicator.idleHoldMilliseconds))
        await waitUntil { viewModel.uiState.workingIndicatorCollapsed }
        #expect(viewModel.uiState.sessionStatusLabel == nil)
        #expect(viewModel.uiState.workingIndicatorLabel == WorkingTurnIndicator.idleLabel)
        #expect(viewModel.uiState.workingIndicatorCollapsed)
        #expect(viewModel.uiState.workingIndicatorFading == false)
    }

    @Test func workingFooter_holdsThroughIdleThenCollapsesAndCancelsOnBusy() async {
        let clock = ChatTestSyncClock(now: 1_000)
        let eventStream = FakeForegroundEventStream()
        let viewModel = makeViewModel(
            eventStream: eventStream,
            sessionRepository: FakeChatSessionRepository(sessionStatusType: "busy"),
            clock: clock
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.workingIndicatorLabel == "Working" }
        #expect(viewModel.uiState.workingIndicatorCollapsed == false)
        #expect(viewModel.uiState.workingIndicatorFading == false)

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        await waitUntil { viewModel.uiState.sessionStatusLabel == nil }
        #expect(viewModel.uiState.workingIndicatorLabel == WorkingTurnIndicator.idleLabel)
        #expect(viewModel.uiState.workingIndicatorFading == false)
        #expect(viewModel.uiState.workingIndicatorCollapsed == false)

        try? await clock.awaitWaiter(milliseconds: Int(WorkingTurnIndicator.idleHoldMilliseconds))
        clock.now += WorkingTurnIndicator.idleHoldMilliseconds
        clock.release(milliseconds: Int(WorkingTurnIndicator.idleHoldMilliseconds))
        await waitUntil { viewModel.uiState.workingIndicatorCollapsed }
        #expect(viewModel.uiState.workingIndicatorLabel == WorkingTurnIndicator.idleLabel)
        #expect(viewModel.uiState.workingIndicatorFading == false)
        #expect(viewModel.uiState.sessionStatusLabel == nil)

        eventStream.emit(
            OpencodeEvent(
                type: "session.status",
                sessionID: "ses-1",
                rawProperties: ["status": .dictionary(["type": .string("busy")])]
            )
        )
        await waitUntil {
            viewModel.uiState.workingIndicatorLabel == "Working" &&
                !viewModel.uiState.workingIndicatorFading &&
                !viewModel.uiState.workingIndicatorCollapsed
        }
        #expect(viewModel.uiState.workingIndicatorLabel == "Working")
        #expect(viewModel.uiState.workingIndicatorFading == false)
        #expect(viewModel.uiState.workingIndicatorCollapsed == false)
    }

    private func finishLongTurn(toolCalls: Int, clock: ChatTestSyncClock) async -> SessionChatViewModel {
        let eventStream = FakeForegroundEventStream()
        let tools: [MessagePart] = (1...toolCalls).map {
            .tool(ToolPart(id: "tool-\($0)", toolName: "read", status: .completed))
        }
        let repository = FakeMessageRepository(messages: [
            Message(id: "msg-1", role: .user, text: "Fix it", model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "msg-2", role: .assistant, text: "", parts: tools, model: nil, mode: nil, sentAtEpochMs: nil),
        ])
        let viewModel = makeViewModel(
            messageRepository: repository,
            eventStream: eventStream,
            sessionRepository: FakeChatSessionRepository(sessionStatusType: "busy"),
            clock: clock
        )
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.sessionStatusLabel != nil }
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        await waitUntil { viewModel.uiState.sessionStatusLabel == nil }
        return viewModel
    }

    @Test func chipTimelineIntro_longTurnFinishes_showsAfterSettleDelayThenEnableTurnsOn() async throws {
        let preferences = SessionChatDisplayPreferences.shared
        preferences.clear()
        defer { preferences.clear() }
        let clock = ChatTestSyncClock(now: 1_000)
        let viewModel = await finishLongTurn(toolCalls: ChipTimelineIntro.minToolCalls, clock: clock)
        defer { viewModel.stopObserving() }
        #expect(viewModel.uiState.chipTimelineIntroVisible == false)

        try await clock.awaitWaiter(milliseconds: Int(ChipTimelineIntro.settleDelayMs))
        clock.release(milliseconds: Int(ChipTimelineIntro.settleDelayMs))
        await waitUntil { viewModel.uiState.chipTimelineIntroVisible }
        #expect(viewModel.uiState.chipTimelineIntroVisible)

        viewModel.enableChipTimelineFromIntro()

        #expect(viewModel.uiState.chipTimelineIntroVisible == false)
        #expect(preferences.chipTimelinePreference)
        #expect(preferences.chipTimelineIntroDone)
        #expect(viewModel.uiState.snackbar?.message == SessionChatViewModel.chipTimelineIntroEnabledNotice)
    }

    @Test func chipTimelineIntro_later_snoozesThreeDays() async throws {
        let preferences = SessionChatDisplayPreferences.shared
        preferences.clear()
        defer { preferences.clear() }
        let clock = ChatTestSyncClock(now: 1_000)
        let viewModel = await finishLongTurn(toolCalls: ChipTimelineIntro.minToolCalls, clock: clock)
        defer { viewModel.stopObserving() }
        try await clock.awaitWaiter(milliseconds: Int(ChipTimelineIntro.settleDelayMs))
        clock.release(milliseconds: Int(ChipTimelineIntro.settleDelayMs))
        await waitUntil { viewModel.uiState.chipTimelineIntroVisible }

        viewModel.postponeChipTimelineIntro()

        #expect(viewModel.uiState.chipTimelineIntroVisible == false)
        #expect(preferences.chipTimelineIntroDone == false)
        #expect(preferences.chipTimelineIntroSnoozedUntilMs == 1_000 + ChipTimelineIntro.snoozeMs)
    }

    @Test func chipTimelineIntro_shortTurn_doesNotWaitToShow() async {
        let preferences = SessionChatDisplayPreferences.shared
        preferences.clear()
        defer { preferences.clear() }
        let clock = ChatTestSyncClock(now: 1_000)
        let viewModel = await finishLongTurn(toolCalls: ChipTimelineIntro.minToolCalls - 1, clock: clock)
        defer { viewModel.stopObserving() }

        await #expect(throws: (any Error).self) {
            try await clock.awaitWaiter(
                milliseconds: Int(ChipTimelineIntro.settleDelayMs),
                timeout: .milliseconds(300)
            )
        }
        #expect(viewModel.uiState.chipTimelineIntroVisible == false)
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

    @Test func remoteCompaction_busyWithCompletedSnapshot_doesNotShowCompacting() async {
        let sessionRepository = FakeChatSessionRepository(sessionStatusType: "busy")
        let viewModel = makeViewModel(
            messageRepository: FakeMessageRepository(messages: [
                Message(
                    id: "u1",
                    role: .user,
                    text: "hello",
                    model: nil,
                    mode: "build",
                    sentAtEpochMs: 1
                ),
                Message(
                    id: "c1",
                    role: .assistant,
                    text: "summary",
                    parts: [.compaction(CompactionPart(id: "c1", auto: false, completed: true))],
                    model: "m",
                    mode: "compaction",
                    sentAtEpochMs: 2
                )
            ]),
            sessionRepository: sessionRepository
        )
        defer { viewModel.stopObserving() }
        await viewModel.loadMessages()
        await waitUntil { viewModel.uiState.sessionStatusLabel == "Working" }
        #expect(viewModel.uiState.workingIndicatorLabel == "Working")
    }

    @Test func dictationInsert_splicesTranscriptAtCaret() {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }
        viewModel.dictationFinishDelayNs = 0

        viewModel.updateComposerText("Hello there", cursor: 5)
        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: " big", volatile: "")
        viewModel.dictationInsert()

        #expect(viewModel.uiState.composer.text == "Hello big there")
        #expect(viewModel.uiState.composer.cursor == 9)
        #expect(viewModel.uiState.dictation == nil)
    }

    @Test func dictationCancel_afterPartialLeavesComposerUntouched() {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }
        viewModel.dictationFinishDelayNs = 0

        viewModel.updateComposerText("Hello there", cursor: 5)
        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: " big", volatile: "ger")
        viewModel.dictationCancel()

        #expect(viewModel.uiState.composer.text == "Hello there")
        #expect(viewModel.uiState.composer.cursor == 5)
        #expect(viewModel.uiState.dictation == nil)
    }

    @Test func dictationAutoCancel_modeChangeAndBackgroundDoNotInsert() {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }
        viewModel.dictationFinishDelayNs = 0

        viewModel.updateComposerText("Hello there", cursor: 5)
        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: " big", volatile: "")
        viewModel.cancelDictationForModeChange()
        #expect(viewModel.uiState.composer.text == "Hello there")
        #expect(viewModel.uiState.composer.cursor == 5)
        #expect(viewModel.uiState.dictation == nil)

        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: " big", volatile: "")
        viewModel.cancelDictationForBackground()
        #expect(viewModel.uiState.composer.text == "Hello there")
        #expect(viewModel.uiState.composer.cursor == 5)
        #expect(viewModel.uiState.dictation == nil)
    }

    @Test func dictationInsert_emptyTranscriptLeavesCardUp() {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }
        viewModel.dictationFinishDelayNs = 0

        viewModel.updateComposerText("Hello there", cursor: 5)
        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: "  ", volatile: " ")
        viewModel.dictationInsert()

        #expect(viewModel.uiState.composer.text == "Hello there")
        #expect(viewModel.uiState.composer.cursor == 5)
        #expect(viewModel.uiState.dictation != nil)
    }

    @Test func dictationModelDownload_updatesOnlyAModelCard() {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }

        viewModel.dictationModelDownload(.modelDownloading, progress: 0.3)
        #expect(viewModel.uiState.dictation == nil)

        viewModel.beginDictationUnavailable(.modelNotInstalled, languageLabel: "English")
        viewModel.dictationModelDownload(.modelDownloading, progress: 0.3)
        #expect(viewModel.uiState.dictation?.phase == .modelDownloading)
        #expect(viewModel.uiState.dictation?.downloadProgress == 0.3)
        #expect(viewModel.uiState.dictation?.languageLabel == "English")

        viewModel.dictationModelDownload(.modelDownloadFailed)
        #expect(viewModel.uiState.dictation?.phase == .modelDownloadFailed)

        viewModel.dictationCancel()
        viewModel.dictationModelDownload(.modelDownloading)
        #expect(viewModel.uiState.dictation == nil)
    }

    @Test func dictationUnavailable_duringFinishingStillInserts() async throws {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }
        viewModel.dictationFinishDelayNs = 30_000_000

        viewModel.updateComposerText("Hello there", cursor: 5)
        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: " big", volatile: "")
        viewModel.dictationInsert()
        viewModel.beginDictationUnavailable(.recognizerError, message: "late")

        #expect(viewModel.uiState.dictation?.phase == .finishing)
        #expect(viewModel.uiState.composer.text == "Hello there")
        try await Task.sleep(nanoseconds: 80_000_000)
        #expect(viewModel.uiState.composer.text == "Hello big there")
        #expect(viewModel.uiState.composer.cursor == 9)
        #expect(viewModel.uiState.dictation == nil)
    }

    @Test func dictationPartial_doesNotChangeComposerText() {
        let viewModel = makeViewModel()
        defer { viewModel.stopObserving() }
        viewModel.dictationFinishDelayNs = 0

        viewModel.updateComposerText("Hello there", cursor: 5)
        viewModel.beginDictation(languageLabel: "English", onDevice: true)
        viewModel.dictationPartial(committed: " big", volatile: "ger")

        #expect(viewModel.uiState.composer.text == "Hello there")
        #expect(viewModel.uiState.composer.cursor == 5)
        #expect(viewModel.uiState.dictation?.committed == " big")
        #expect(viewModel.uiState.dictation?.volatile == "ger")
    }

    @Test func sessionChatFocusesAndReleasesViewReporter() {
        let reporter = RecordingSessionViewReporter()
        let viewModel = makeViewModel(sessionViewReporter: reporter)

        #expect(reporter.calls == ["focus:ses-1"])
        viewModel.stopObserving()
        #expect(reporter.calls == ["focus:ses-1", "release:ses-1"])
    }

    private func makeViewModel(
        messageRepository: (any MessageRepository)? = nil,
        eventStream: FakeForegroundEventStream? = nil,
        sessionModelSelectionStore: (any SessionModelSelectionStore)? = nil,
        composerDraftStore: (any ComposerDraftStore)? = nil,
        modelRepository: any ModelRepository = FakeModelRepository(),
        modelCatalogStore: ModelCatalogStore? = nil,
        activeConnectionManager: ActiveConnectionManager? = nil,
        commandRepository: FakeCommandRepository = FakeCommandRepository(),
        agentRepository: FakeAgentRepository = FakeAgentRepository(),
        sessionRepository: FakeChatSessionRepository? = nil,
        permissionLiveStore: (any PermissionLiveStore)? = nil,
        questionLiveStore: (any QuestionLiveStore)? = nil,
        sessionID: String = "ses-1",
        sessionTitle: String = "Title",
        serverProtocol: ServerProtocol = .v1,
        liveUpdates: any LiveUpdates = FixedLiveUpdates(),
        clock: any SyncClock = SystemSyncClock(),
        catalogRetryDelaysMs: [Int] = [],
        sessionViewReporter: (any SessionViewReporter)? = nil,
        projectFilesRepository: (any ProjectFilesRepository)? = nil,
        worktree: String = "/repo",
        connectionProfileStore: any ConnectionProfileStore = EmptyConnectionProfileStore(),
        worktreeRepository: any WorktreeRepository = UnavailableWorktreeRepository(),
        worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache()
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
        let draftStore = composerDraftStore ?? InMemoryComposerDraftStore()
        let activeConnectionManager = activeConnectionManager ?? Self.makeActiveConnectionManager(serverProtocol: serverProtocol)
        // A fresh store per view model keeps each test's first open cold unless it shares one.
        let catalogStore = modelCatalogStore ?? ModelCatalogStore(
            modelRepository: modelRepository,
            agentRepository: agentRepository,
            activeConnectionManager: activeConnectionManager
        )
        let viewModel = SessionChatViewModel(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            worktree: worktree,
            messageRepository: repo,
            modelCatalogStore: catalogStore,
            commandRepository: commandRepository,
            agentRepository: agentRepository,
            sessionRepository: sessionRepository,
            permissionLiveStore: permissionLiveStore,
            questionLiveStore: questionLiveStore,
            sessionReadyStore: EmptySessionReadyStore(),
            sessionViewReporter: sessionViewReporter,
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: eventStream,
            sessionModelSelectionStore: selectionStore,
            composerDraftStore: draftStore,
            activeConnectionManager: activeConnectionManager,
            connectionProfileStore: connectionProfileStore,
            newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                store: selectionStore,
                modelCatalogStore: catalogStore
            ),
            liveUpdates: liveUpdates,
            clock: clock,
            projectFilesRepository: projectFilesRepository,
            catalogRetryDelaysMs: catalogRetryDelaysMs,
            worktreeRepository: worktreeRepository,
            worktreeInventoryCache: worktreeInventoryCache
        )
        viewModel.startObserving()
        return viewModel
    }

    private func sharedCatalogFixture() -> (ModelSelectionOption, ModelSelectionOption, ModelSelectionCatalog) {
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
        return (
            defaultOption,
            pickedOption,
            ModelSelectionCatalog(options: [defaultOption, pickedOption], serverDefault: defaultOption)
        )
    }

    /// A store that already holds the catalog and agents for `/repo`, as after an earlier Session Chat.
    private func warmedCatalogStore(
        models: GatedModelRepository
    ) async -> (ModelCatalogStore, ActiveConnectionManager, FakeAgentRepository) {
        let agents = FakeAgentRepository()
        let connection = Self.makeActiveConnectionManager()
        let store = ModelCatalogStore(modelRepository: models, agentRepository: agents, activeConnectionManager: connection)
        _ = try? await store.refreshCatalog(worktree: "/repo")
        _ = try? await store.refreshSelectableAgents(worktree: "/repo")
        return (store, connection, agents)
    }

    private static let testConnectionIdentity = ModelSelectionConnectionIdentity(
        serverBaseURL: "http://127.0.0.1:4096/",
        username: "opencode"
    )

    private static func makeActiveConnectionManager(serverProtocol: ServerProtocol = .v1) -> ActiveConnectionManager {
        let activeConnectionManager = ActiveConnectionManager()
        activeConnectionManager.apply(
            ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""),
            serverProtocol: serverProtocol
        )
        return activeConnectionManager
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
        mime: String = "text/plain",
        previewData: Data? = nil
    ) -> OpenCode_Mobile.Attachment {
        OpenCode_Mobile.Attachment(
            id: id,
            filename: filename,
            mime: mime,
            kind: kind,
            dataURL: "data:\(mime);base64,SGVsbG8=",
            previewData: previewData
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
        timeoutMs: Int = 5_000,
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

private nonisolated struct StubProjectFilesRepository: ProjectFilesRepository, Sendable {
    var entries: [ProjectEntry]

    func listEntries(worktree _: String, relativeDirectory _: String) async throws -> [ProjectEntry] {
        []
    }

    func findFiles(worktree _: String, query _: String, limit _: Int) async throws -> [ProjectEntry] {
        entries
    }

    func readFile(worktree _: String, relativePath _: String) async throws -> ProjectFileContent {
        throw ProjectFilesError.notFound
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

    func observeCatchUpChrome() -> AsyncStream<Bool> {
        sync.observeCatchUpChrome()
    }

    func observeCatchUpChrome(sessionID: String, directory: String?) -> AsyncStream<Bool> {
        sync.observeCatchUpChrome(sessionID: sessionID, directory: directory)
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

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {
        try await fake.runShellCommand(sessionID: sessionID, options: options, directory: directory)
    }
}

@MainActor
private final class FakeObservingMessageRepository: MessageRepository, @unchecked Sendable {
    private let park = ObservationPark()
    private var publish: ((MessageListUpdate) -> Void)?
    private var observation: SharedLiveObservation<MessageListUpdate>?

    deinit {
        park.finish()
    }

    private func ensureObservation() -> SharedLiveObservation<MessageListUpdate> {
        if let observation, !observation.isTerminated {
            return observation
        }
        let park = park
        let created = SharedLiveObservation<MessageListUpdate> { [park] yield in
            Task { @MainActor [weak self] in
                self?.publish = yield
                await park.holdUntilCancelled()
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

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {}

    func emit(_ messages: [Message]) {
        publish?(.messages(messages, isFromCache: false))
    }

    func emitLoadFailed(_ error: Error) {
        publish?(.loadFailed(error))
    }
}

/// Resumes every parked waiter on cancel or shutdown. A single stored
/// continuation would be overwritten and leak the previous waiter.
private final class ObservationPark: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var shutdown = false

    func holdUntilCancelled() async {
        let ticket = UUID()
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                if shutdown {
                    lock.unlock()
                    continuation.resume()
                    return
                }
                waiters[ticket] = continuation
                // onCancel can fire before the waiter is stored; take ownership
                // here so the continuation is not parked after cancel already ran.
                if Task.isCancelled, let pending = waiters.removeValue(forKey: ticket) {
                    lock.unlock()
                    pending.resume()
                    return
                }
                lock.unlock()
            }
        } onCancel: {
            resume(ticket)
        }
    }

    func finish() {
        lock.lock()
        shutdown = true
        let pending = Array(waiters.values)
        waiters.removeAll()
        lock.unlock()
        pending.forEach { $0.resume() }
    }

    private func resume(_ ticket: UUID) {
        lock.lock()
        let pending = waiters.removeValue(forKey: ticket)
        lock.unlock()
        pending?.resume()
    }
}

private final class FakeMessageRepository: MessageRepository, @unchecked Sendable {
    var messages: [Message]
    let shouldFailLoad: Bool
    let shouldFailSend: Bool
    let failListMessagesAfterSend: Bool
    let echoSentPrompt: Bool
    let holdSend: Bool
    var sendPromptAsyncCalled = false
    var sentOptions: PromptSendOptions?
    var shellRequests: [(options: ShellCommandOptions, directory: String?)] = []
    var shellError: Error?
    private var listMessagesCallCount = 0
    /// Holds `listMessages` until opened.
    var listHold: AsyncGate?
    private var sendContinuation: CheckedContinuation<Void, Never>?
    private var sendReleaseRequested = false

    init(
        messages: [Message] = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
        ],
        shouldFailLoad: Bool = false,
        shouldFailSend: Bool = false,
        failListMessagesAfterSend: Bool = false,
        echoSentPrompt: Bool = false,
        holdSend: Bool = false
    ) {
        self.messages = messages
        self.shouldFailLoad = shouldFailLoad
        self.shouldFailSend = shouldFailSend
        self.failListMessagesAfterSend = failListMessagesAfterSend
        self.echoSentPrompt = echoSentPrompt
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
        // Post-send refresh is the reload-after-send path. Live observe still
        // lists through `listMessages` and must not inherit this failure, or a
        // late subscribe snapshot flips a ready timeline to full-screen error.
        if failListMessagesAfterSend, sendPromptAsyncCalled {
            throw MessageError.transport("reload failed")
        }
        return try await listMessages(sessionID: sessionID, directory: directory)
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
        await listHold?.wait()
        if shouldFailLoad {
            throw MessageError.transport("load failed")
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
        if let handler = PromptSendProgressReporting.handler, !options.attachments.isEmpty {
            handler(1, 4)
        }
        if shouldFailSend {
            if holdSend, !sendReleaseRequested {
                await withCheckedContinuation { sendContinuation = $0 }
            }
            throw MessageError.transport("send failed")
        } else if holdSend, !sendReleaseRequested {
            await withCheckedContinuation { sendContinuation = $0 }
        }
        if echoSentPrompt, !options.text.isEmpty {
            messages.append(
                Message(id: "msg-sent", role: .user, text: options.text, model: nil, mode: nil, sentAtEpochMs: nil)
            )
        }
    }

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {
        shellRequests.append((options, directory))
        if let shellError { throw shellError }
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

/// Answers with `catalog` while open; counts calls and can fail instead.
private final class GatedModelRepository: ModelRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var isOpen = true
    private var shouldFail = false
    private var catalog: ModelSelectionCatalog
    private var _calls = 0

    init(catalog: ModelSelectionCatalog) {
        self.catalog = catalog
    }

    var calls: Int { withLock { _calls } }

    func open() { withLock { isOpen = true } }
    func close() { withLock { isOpen = false } }
    func setFailure(_ fail: Bool) { withLock { shouldFail = fail } }
    func setCatalog(_ catalog: ModelSelectionCatalog) { withLock { self.catalog = catalog } }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        withLock { _calls += 1 }
        while !withLock({ isOpen }) {
            try? await Task.sleep(for: .milliseconds(5))
        }
        let (fail, catalog) = withLock { (shouldFail, self.catalog) }
        if fail { throw ModelSelectionError.transport("offline") }
        return catalog
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// Returns each queued catalog once, then the default single-model catalog.
private final class SequencedModelRepository: ModelRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var catalogs: [ModelSelectionCatalog]
    private var _calls = 0
    private let fallback = FakeModelRepository().catalog

    init(catalogs: [ModelSelectionCatalog]) {
        self.catalogs = catalogs
    }

    var calls: Int {
        lock.lock()
        defer { lock.unlock() }
        return _calls
    }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        lock.lock()
        defer { lock.unlock() }
        _calls += 1
        return catalogs.isEmpty ? fallback : catalogs.removeFirst()
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
    var selectableAgents: [SelectableAgent]
    private var blockSelectableAgents: Bool

    init(
        agents: [MentionableAgent] = [
            MentionableAgent(name: "explore", description: "Read-only explorer"),
            MentionableAgent(name: "general", description: "General helper"),
        ],
        selectableAgents: [SelectableAgent] = SelectableAgent.fallback,
        blockSelectableAgents: Bool = false
    ) {
        self.agents = agents
        self.selectableAgents = selectableAgents
        self.blockSelectableAgents = blockSelectableAgents
    }

    func releaseSelectableAgents() {
        blockSelectableAgents = false
    }

    func listSelectableAgents(directory: String?) async throws -> [SelectableAgent] {
        while blockSelectableAgents { await Task.yield() }
        return selectableAgents
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

private final class InMemoryComposerDraftStore: ComposerDraftStore, @unchecked Sendable {
    private var saved: [String: ComposerDraft] = [:]

    func load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> ComposerDraft? {
        saved[key(connectionIdentity, worktree, sessionID)]
    }

    func save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        draft: ComposerDraft
    ) {
        saved[key(connectionIdentity, worktree, sessionID)] = draft
    }

    func delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) {
        saved.removeValue(forKey: key(connectionIdentity, worktree, sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) {
        saved.keys
            .filter { $0.hasPrefix(connectionIdentity.serverBaseURL) && $0.hasSuffix("|\(sessionID)") }
            .forEach { saved.removeValue(forKey: $0) }
    }

    func clearAll() {
        saved.removeAll()
    }

    private func key(
        _ identity: ModelSelectionConnectionIdentity,
        _ worktree: String,
        _ sessionID: String
    ) -> String {
        "\(identity.serverBaseURL)|\(worktree)|\(sessionID)"
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
    var childSessions: [Session] = []
    var childSessionFailuresRemaining = 0
    private(set) var childSessionRequests: [String] = []
    private let pendingPermissions: [PermissionRequest]
    private let pendingQuestions: [QuestionRequest]
    private let permissionResponseError: SessionError?
    private let questionResponseError: SessionError?
    private let listSessionsError: SessionError?
    private let sessionDiffs: [FileDiff]
    private let sessionDiffError: SessionError?
    private let sessionDiffProvider: (@Sendable (String?) async throws -> [FileDiff])?
    private var sessionStatusType: String
    private let abortErrorsByID: [String: SessionError]
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
    private(set) var listSessionsCallCount = 0
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
    private(set) var forkSessionCallCount = 0
    private(set) var updateSessionCallCount = 0
    private(set) var abortCallCount = 0
    private(set) var abortSessionIDs: [String] = []
    private(set) var revertRequest: RevertRequest?
    private(set) var unrevertRequest: String?
    private(set) var commitRevertRequests: [String] = []
    /// Runs when a revert commit arrives, before it succeeds or fails.
    var onCommitRevert: (() -> Void)?
    private(set) var summarizeRequest: SummarizeRequest?
    private let getSessionError: SessionError?
    private let createSessionError: SessionError?
    private let forkSessionError: SessionError?
    private let revertError: SessionError?
    private let unrevertError: SessionError?
    private let commitRevertError: SessionError?
    private let summarizeError: SessionError?
    private let summarizeHold: AsyncGate?
    private let compactStart: CompactionStart

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
        abortErrorsByID: [String: SessionError] = [:],
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
        commitRevertError: SessionError? = nil,
        summarizeError: SessionError? = nil,
        summarizeHold: AsyncGate? = nil,
        compactStart: CompactionStart = .finished
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
        self.abortErrorsByID = abortErrorsByID
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
        self.commitRevertError = commitRevertError
        self.summarizeError = summarizeError
        self.summarizeHold = summarizeHold
        self.compactStart = compactStart
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
        listSessionsCallCount += 1
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
        updateSessionCallCount += 1
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
    func listChildSessions(id: String) async throws -> [Session] {
        childSessionRequests.append(id)
        if childSessionFailuresRemaining > 0 {
            childSessionFailuresRemaining -= 1
            throw SessionError.http(statusCode: 503, message: "unavailable")
        }
        return childSessions
    }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        forkSessionRequest = ForkRequest(id: id, atMessageID: atMessageID)
        forkSessionCallCount += 1
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
        abortSessionIDs.append(id)
        if let abortError = abortErrorsByID[id] { throw abortError }
        sessionStatusType = "idle"
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
    func compactSession(id: String, providerID: String, modelID: String) async throws -> CompactionStart {
        _ = try await summarizeSession(id: id, providerID: providerID, modelID: modelID)
        return compactStart
    }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        revertRequest = RevertRequest(id: id, messageID: messageID, partID: partID)
        if let revertError { throw revertError }
        var session = getSessionResult ?? Session(
            id: id,
            title: "Title",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: nil
        )
        session.revert = SessionRevert(messageID: messageID)
        getSessionResult = session
        return true
    }
    func unrevertSession(id: String) async throws -> Bool {
        unrevertRequest = id
        if let unrevertError { throw unrevertError }
        if var session = getSessionResult {
            session.revert = nil
            getSessionResult = session
        }
        return true
    }
    func commitRevert(id: String) async throws -> Bool {
        commitRevertRequests.append(id)
        onCommitRevert?()
        if let commitRevertError { throw commitRevertError }
        if var session = getSessionResult {
            session.revert = nil
            getSessionResult = session
        }
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

@MainActor
private final class ScriptedPermissionLiveStore: PermissionLiveStore {
    private var continuation: AsyncStream<[PermissionRequest]>.Continuation?
    private var buffered: [[PermissionRequest]] = []

    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        AsyncStream { continuation in
            self.continuation = continuation
            for snapshot in self.buffered {
                continuation.yield(snapshot)
            }
            self.buffered.removeAll()
        }
    }

    func emit(_ requests: [PermissionRequest]) {
        if let continuation {
            continuation.yield(requests)
        } else {
            buffered.append(requests)
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

private final class ChatTestSyncClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    var now: Int64
    private var waiters: [Int: [CheckedContinuation<Void, Never>]] = [:]

    init(now: Int64 = 1_000) {
        self.now = now
    }

    func nowMs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return now
    }

    func sleep(milliseconds: Int) async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                waiters[milliseconds, default: []].append(continuation)
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            if var bucket = waiters[milliseconds], !bucket.isEmpty {
                let continuation = bucket.removeFirst()
                waiters[milliseconds] = bucket
                lock.unlock()
                continuation.resume()
                return
            }
            lock.unlock()
        }
    }

    func release(milliseconds: Int) {
        lock.lock()
        let pending = waiters.removeValue(forKey: milliseconds) ?? []
        lock.unlock()
        pending.forEach { $0.resume() }
    }

    func awaitWaiter(milliseconds: Int, timeout: Duration = .seconds(3)) async throws {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            lock.lock()
            let count = waiters[milliseconds]?.count ?? 0
            lock.unlock()
            if count > 0 { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw NSError(domain: "ChatTestSyncClock", code: 1)
    }
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

/// Added paths for any server; no stored profile.
private struct AddedPathsConnectionProfileStore: ConnectionProfileStore {
    let added: [String]

    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { added }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}

/// Every inventory load waits until `open()`, then answers `load`.
private final class GatedWorktreeRepository: WorktreeRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let answer: WorktreeLoad
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var answeredCount = 0

    init(load: WorktreeLoad) {
        answer = load
    }

    var answered: Int { lock.withLock { answeredCount } }

    func open() {
        let resumed: [CheckedContinuation<Void, Never>] = lock.withLock {
            isOpen = true
            defer { waiters = [] }
            return waiters
        }
        resumed.forEach { $0.resume() }
    }

    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { await gated() }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { await gated() }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { await gated() }
    func onDisconnected() {}
    func onConnection(key: String) {}

    private func gated() async -> WorktreeLoad {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumeNow: Bool = lock.withLock {
                if isOpen { return true }
                waiters.append(continuation)
                return false
            }
            if resumeNow { continuation.resume() }
        }
        lock.withLock { answeredCount += 1 }
        return answer
    }
}

private struct EmptyConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}

@MainActor
private final class RecordingSessionViewReporter: SessionViewReporter {
    private(set) var calls: [String] = []

    func focus(sessionId: String) { calls.append("focus:\(sessionId)") }
    func release(sessionId: String) { calls.append("release:\(sessionId)") }
    func clearCache() { calls.append("clearCache") }
}
