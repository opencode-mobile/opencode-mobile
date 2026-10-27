package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.CommandError
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.command.CommandRunOptions
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.command.ServerCommandSource
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.V2ServerFeatures
import dev.opencode.mobile.domain.connection.testConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.event.LiveUpdatesChrome
import dev.opencode.mobile.domain.event.LiveUpdatesLabel
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.EmptySessionReadyStore
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.NoOpSessionViewReporter
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionViewReporter
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentIngestionResult
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentLimits
import dev.opencode.mobile.domain.message.AttachmentUpload
import dev.opencode.mobile.domain.message.AttachmentUploadResult
import dev.opencode.mobile.domain.message.AttachmentUploadService
import dev.opencode.mobile.domain.message.UnavailableAttachmentUploadService
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.PromptSendProgressReporter
import dev.opencode.mobile.domain.message.ShellCommandOptions
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionError
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.preferences.ChipTimelineIntro
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.InMemoryPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.session.CompactionStart
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionRevert
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SideQuestionStore
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.CreatedWorktree
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.network.ServerConnection
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer
import dev.opencode.mobile.ui.sessionchat.region.SessionChatReviewChanges
import dev.opencode.mobile.ui.sessionchat.region.SessionChatSideQuestionsCopy
import dev.opencode.mobile.ui.worktree.NewWorktreeAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SessionChatDisplayPreferences.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SessionChatDisplayPreferences.clear()
    }

    private fun longTurnRepository(toolCalls: Int) =
        FakeMessageRepository(
            messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Fix it"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "",
                        parts =
                            (1..toolCalls).map { index ->
                                MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-$index")
                            },
                    ),
                ),
        )

    private suspend fun TestScope.finishLongTurn(toolCalls: Int): SessionChatViewModel {
        val repository = longTurnRepository(toolCalls)
        val viewModel =
            createViewModel(
                repository = repository,
                sessionRepository = FakeSessionRepository(sessionStatusType = "busy"),
            )
        viewModel.bind("ses-1", "Title", "/repo")
        advanceUntilIdle()
        repository.eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
        runCurrent()
        return viewModel
    }

    @Test
    fun chipTimelineIntro_longTurnFinishes_showsAfterSettleDelay() =
        runTest(testDispatcher) {
            val viewModel = finishLongTurn(ChipTimelineIntro.MIN_TOOL_CALLS)

            testDispatcher.scheduler.advanceTimeBy(ChipTimelineIntro.SETTLE_DELAY_MILLIS - 1)
            runCurrent()
            assertFalse(viewModel.uiState.value.chipTimelineIntroVisible)

            testDispatcher.scheduler.advanceTimeBy(1)
            runCurrent()
            assertTrue(viewModel.uiState.value.chipTimelineIntroVisible)
        }

    @Test
    fun chipTimelineIntro_shortTurn_doesNotShow() =
        runTest(testDispatcher) {
            val viewModel = finishLongTurn(ChipTimelineIntro.MIN_TOOL_CALLS - 1)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.chipTimelineIntroVisible)
        }

    @Test
    fun chipTimelineIntro_enable_turnsOnChipTimelineAndEndsIntro() =
        runTest(testDispatcher) {
            val viewModel = finishLongTurn(ChipTimelineIntro.MIN_TOOL_CALLS)
            advanceUntilIdle()

            viewModel.enableChipTimelineFromIntro()

            assertFalse(viewModel.uiState.value.chipTimelineIntroVisible)
            assertTrue(SessionChatDisplayPreferences.chipTimelinePreference)
            assertTrue(SessionChatDisplayPreferences.chipTimelineIntroDone)
            assertEquals(
                SessionChatViewModel.CHIP_TIMELINE_INTRO_ENABLED_NOTICE,
                viewModel.uiState.value.snackbar?.message,
            )
        }

    @Test
    fun chipTimelineIntro_later_snoozesThenSecondLaterEndsIntro() =
        runTest(testDispatcher) {
            val viewModel = finishLongTurn(ChipTimelineIntro.MIN_TOOL_CALLS)
            advanceUntilIdle()

            viewModel.postponeChipTimelineIntro()

            assertFalse(viewModel.uiState.value.chipTimelineIntroVisible)
            assertFalse(SessionChatDisplayPreferences.chipTimelineIntroDone)
            assertEquals(
                testDispatcher.scheduler.currentTime + ChipTimelineIntro.SNOOZE_MILLIS,
                SessionChatDisplayPreferences.chipTimelineIntroSnoozedUntilMillis,
            )

            SessionChatDisplayPreferences.postponeChipTimelineIntro(nowMillis = Long.MAX_VALUE / 2)
            assertTrue(SessionChatDisplayPreferences.chipTimelineIntroDone)
        }

    @Test
    fun chipTimelineIntro_dontShowAgain_endsIntroWithoutTurningOn() =
        runTest(testDispatcher) {
            val viewModel = finishLongTurn(ChipTimelineIntro.MIN_TOOL_CALLS)
            advanceUntilIdle()

            viewModel.dismissChipTimelineIntro()

            assertFalse(viewModel.uiState.value.chipTimelineIntroVisible)
            assertTrue(SessionChatDisplayPreferences.chipTimelineIntroDone)
            assertFalse(SessionChatDisplayPreferences.chipTimelinePreference)
        }

    @Test
    fun chipTimelineIntro_settingsChange_endsIntro() =
        runTest(testDispatcher) {
            SessionChatDisplayPreferences.updateChipTimeline(false)
            val viewModel = finishLongTurn(ChipTimelineIntro.MIN_TOOL_CALLS)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.chipTimelineIntroVisible)
        }

    /** An existing install that has not answered the Timeline Style picker yet (#805). */
    private fun pendTimelineStylePicker() {
        SessionChatDisplayPreferences.initialize(InMemoryPreferences())
        SessionChatDisplayPreferences.resolveTimelineForInstall(existingInstall = true)
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    private fun historyRepository() = longTurnRepository(toolCalls = 1)

    private fun TestScope.awaitReadyTimeline(viewModel: SessionChatViewModel) {
        runCurrent()
        var steps = 0
        while (viewModel.uiState.value.timeline != SessionChatTimelineState.Ready && steps++ < 10_000) {
            testDispatcher.scheduler.advanceTimeBy(1)
            runCurrent()
        }
        assertEquals(SessionChatTimelineState.Ready, viewModel.uiState.value.timeline)
    }

    @Test
    fun timelineStylePicker_pending_showsAfterDelayOnceHistoryLoads() =
        runTest(testDispatcher) {
            pendTimelineStylePicker()
            val viewModel = createViewModel(repository = historyRepository())

            viewModel.bind("ses-1", "Title", "/repo")
            awaitReadyTimeline(viewModel)
            testDispatcher.scheduler.advanceTimeBy(SessionChatViewModel.TIMELINE_STYLE_PICKER_DELAY_MILLIS - 1)
            runCurrent()
            assertFalse(viewModel.uiState.value.timelineStylePickerVisible)

            testDispatcher.scheduler.advanceTimeBy(1)
            runCurrent()
            val state = viewModel.uiState.value
            assertTrue(state.timelineStylePickerVisible)
            // Activity Card first, then the other offered styles in their usual order.
            val expectedStyles =
                listOf(SessionChatTimelineStyle.ActivityCard) +
                    SessionChatDisplayPreferences.availableTimelineStyles.filter { it != SessionChatTimelineStyle.ActivityCard }
            assertEquals(expectedStyles, state.timelineStylePickerOptions.map { it.style })
            assertEquals(SessionChatTimelineStyle.Standard, expectedStyles[1])
            assertEquals(
                listOf(SessionChatTimelineStyleOption.RECOMMENDED_TAG, SessionChatTimelineStyleOption.CURRENT_TAG) +
                    List(expectedStyles.size - 2) { null },
                state.timelineStylePickerOptions.map { it.tag },
            )
        }

    @Test
    fun timelineStylePicker_notPending_neverShows() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(repository = historyRepository())

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.timelineStylePickerVisible)
            assertTrue(
                viewModel.uiState.value.timelineStylePickerOptions
                    .isEmpty(),
            )
        }

    @Test
    fun timelineStylePicker_emptySession_doesNotShow() =
        runTest(testDispatcher) {
            pendTimelineStylePicker()
            val viewModel = createViewModel(repository = FakeMessageRepository(messages = emptyList()))

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(SessionChatTimelineState.Empty, viewModel.uiState.value.timeline)
            assertFalse(viewModel.uiState.value.timelineStylePickerVisible)
            assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
        }

    @Test
    fun timelineStylePicker_blockedByPermission_skipsForThisViewModel() =
        runTest(testDispatcher) {
            pendTimelineStylePicker()
            val viewModel =
                createViewModel(
                    repository = historyRepository(),
                    sessionRepository = FakeSessionRepository(sessions = listOf(Session(id = "ses-1", title = "Title"))),
                    permissionLiveStore = FakePermissionLiveStore(listOf(permissionRequest("permission-1", "ses-1"))),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.permissionRequest)
            assertFalse(viewModel.uiState.value.timelineStylePickerVisible)
            assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
        }

    @Test
    fun timelineStylePicker_choose_storesStyleHidesAndNotices() =
        runTest(testDispatcher) {
            pendTimelineStylePicker()
            val viewModel = createViewModel(repository = historyRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.timelineStylePickerVisible)

            viewModel.chooseTimelineStyleFromPicker(SessionChatTimelineStyle.StepRail)

            assertFalse(viewModel.uiState.value.timelineStylePickerVisible)
            assertEquals(SessionChatTimelineStyle.StepRail, SessionChatDisplayPreferences.timelineStylePreference)
            assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
            assertEquals(
                "Timeline: Step Rail. Change it any time in Settings.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun timelineStylePicker_dismiss_storesActivityCardHidesAndNotices() =
        runTest(testDispatcher) {
            pendTimelineStylePicker()
            val viewModel = createViewModel(repository = historyRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.timelineStylePickerVisible)

            viewModel.dismissTimelineStylePicker()

            assertFalse(viewModel.uiState.value.timelineStylePickerVisible)
            assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatDisplayPreferences.timelineStylePreference)
            assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
            assertEquals(
                "Timeline: Activity Card. Change it any time in Settings.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun archiveSession_successLeavesSession() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.archiveSession()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.LifecycleRequest(id = "ses-1", directory = "/repo"),
                sessionRepository.archiveRequest,
            )
            assertTrue(viewModel.uiState.value.shouldLeaveSession)
            assertEquals(false, viewModel.uiState.value.isLifecycleActionInFlight)
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun archiveSession_failureKeepsSessionWithError() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(archiveError = SessionError.Http(500, "boom"))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.archiveSession()
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.shouldLeaveSession)
            assertEquals(
                "Couldn't archive session.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun deleteSession_successLeavesSession() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.deleteSession()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.LifecycleRequest(id = "ses-1", directory = "/repo"),
                sessionRepository.deleteRequest,
            )
            assertTrue(viewModel.uiState.value.shouldLeaveSession)
        }

    @Test
    fun deleteSession_failureKeepsSessionWithError() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(deleteError = SessionError.Http(500, "boom"))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.deleteSession()
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.shouldLeaveSession)
            assertEquals(
                "Couldn't delete session.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun deleteSession_falseResultKeepsSessionWithError() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(deleteResult = false)
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.deleteSession()
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.shouldLeaveSession)
            assertEquals(
                "Couldn't delete session.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun archiveSession_disablesWhileInFlight() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val sessionRepository = FakeSessionRepository(archiveHoldUntil = gate)
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.archiveSession()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLifecycleActionInFlight)

            viewModel.archiveSession()
            advanceUntilIdle()
            assertEquals(1, sessionRepository.archiveCallCount)

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.shouldLeaveSession)
        }

    @Test
    fun orderedPermissionRequests_prioritizesRootAndIncludesNewDescendants() {
        val rootRequest = permissionRequest("root-request", "root")
        val childRequest = permissionRequest("child-request", "child")
        val newChildRequest = permissionRequest("new-child-request", "new-child")

        val ordered =
            orderedPermissionRequests(
                rootSessionId = "root",
                sessions =
                    listOf(
                        Session(id = "child", title = "Child", parentId = "root"),
                        Session(id = "new-child", title = "New child", parentId = "root"),
                    ),
                requests = listOf(childRequest, newChildRequest, rootRequest),
            )

        assertEquals(listOf("root-request", "child-request", "new-child-request"), ordered.map { it.id })
    }

    @Test
    fun stalePermissionResponse_refreshesAndRemovesMissingRequest() =
        runTest(testDispatcher) {
            val request = permissionRequest("permission-1", "ses-1")
            val sessionRepository =
                FakeSessionRepository(
                    sessions = listOf(Session(id = "ses-1", title = "Title")),
                    pendingPermissions = emptyList(),
                    permissionResponseError = SessionError.Http(404, "Missing"),
                )
            val permissionLiveStore = FakePermissionLiveStore(listOf(request))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    permissionLiveStore = permissionLiveStore,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                "permission-1",
                viewModel.uiState.value.permissionRequest
                    ?.id,
            )

            viewModel.allowPermissionOnce(request.id, request.sessionId)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.permissionRequest)
            assertEquals(1, sessionRepository.pendingPermissionLoads)
            assertEquals("permission-1", sessionRepository.respondedPermissionId)
            assertEquals("ses-1", sessionRepository.respondedSessionId)
        }

    @Test
    fun latePermissionDecision_usesRenderedRequestIdentity() =
        runTest(testDispatcher) {
            val first = permissionRequest("permission-1", "ses-1")
            val second = permissionRequest("permission-2", "child")
            val sessionRepository =
                FakeSessionRepository(
                    sessions =
                        listOf(
                            Session(id = "ses-1", title = "Title"),
                            Session(id = "child", title = "Child", parentId = "ses-1"),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    permissionLiveStore = FakePermissionLiveStore(listOf(first, second)),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.allowPermissionOnce(second.id, second.sessionId)
            advanceUntilIdle()

            assertEquals("permission-2", sessionRepository.respondedPermissionId)
            assertEquals("child", sessionRepository.respondedSessionId)
        }

    @Test
    fun permissionTopologyFailure_isReported() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = FakeSessionRepository(listSessionsError = IllegalStateException("offline")),
                    permissionLiveStore =
                        FakePermissionLiveStore(listOf(permissionRequest("permission-1", "child"))),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.permissionRequest)
            assertEquals(
                "Unable to refresh permission session tree",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun permissionSnapshot_fetchesSessionList_onlyForUnknownSubagentSessions() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessions =
                        listOf(
                            Session(id = "ses-1", title = "Title"),
                            Session(id = "child", title = "Child", parentId = "ses-1"),
                        ),
                )
            val permissionLiveStore =
                FakePermissionLiveStore(listOf(permissionRequest("permission-1", "ses-1")))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    permissionLiveStore = permissionLiveStore,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                "permission-1",
                viewModel.uiState.value.permissionRequest
                    ?.id,
            )
            assertEquals(
                "a root-session-only snapshot must not fan out to listSessions",
                0,
                sessionRepository.listSessionsCallCount,
            )

            permissionLiveStore.requests.value =
                listOf(
                    permissionRequest("permission-1", "ses-1"),
                    permissionRequest("permission-2", "ses-1"),
                )
            advanceUntilIdle()
            assertEquals(
                "repeated root-session snapshots must stay free of listSessions",
                0,
                sessionRepository.listSessionsCallCount,
            )

            permissionLiveStore.requests.value =
                listOf(
                    permissionRequest("permission-1", "ses-1"),
                    permissionRequest("permission-3", "child"),
                )
            advanceUntilIdle()
            assertEquals(
                "an unknown subagent session must trigger exactly one listSessions",
                1,
                sessionRepository.listSessionsCallCount,
            )

            permissionLiveStore.requests.value =
                listOf(permissionRequest("permission-3", "child"))
            advanceUntilIdle()
            assertEquals(
                "a session already in the cached tree must not trigger another listSessions",
                1,
                sessionRepository.listSessionsCallCount,
            )
        }

    @Test
    fun questionSnapshot_fetchesSessionList_onlyForUnknownSubagentSessions() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessions =
                        listOf(
                            Session(id = "ses-1", title = "Title"),
                            Session(id = "child", title = "Child", parentId = "ses-1"),
                        ),
                )
            val questionLiveStore =
                FakeQuestionLiveStore(listOf(questionRequest("que-1", "ses-1")))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    questionLiveStore = questionLiveStore,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                "a root-session-only snapshot must not fan out to listSessions",
                0,
                sessionRepository.listSessionsCallCount,
            )

            questionLiveStore.requests.value = listOf(questionRequest("que-2", "child"))
            advanceUntilIdle()
            assertEquals(
                "an unknown subagent session must trigger exactly one listSessions",
                1,
                sessionRepository.listSessionsCallCount,
            )

            questionLiveStore.requests.value =
                listOf(questionRequest("que-2", "child"), questionRequest("que-3", "child"))
            advanceUntilIdle()
            assertEquals(
                "a session already in the cached tree must not trigger another listSessions",
                1,
                sessionRepository.listSessionsCallCount,
            )
        }

    @Test
    fun permissionRequest_takesPriorityOverQuestionRequest() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository =
                        FakeSessionRepository(sessions = listOf(Session(id = "ses-1", title = "Title"))),
                    permissionLiveStore = FakePermissionLiveStore(listOf(permissionRequest("permission-1", "ses-1"))),
                    questionLiveStore = FakeQuestionLiveStore(listOf(questionRequest("que-1", "ses-1"))),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "permission-1",
                viewModel.uiState.value.permissionRequest
                    ?.id,
            )
            assertEquals(null, viewModel.uiState.value.questionRequest)
        }

    @Test
    fun staleQuestionReply_refreshesAndRemovesMissingRequest() =
        runTest(testDispatcher) {
            val request = questionRequest("que-1", "ses-1")
            val sessionRepository =
                FakeSessionRepository(
                    sessions = listOf(Session(id = "ses-1", title = "Title")),
                    pendingQuestions = emptyList(),
                    questionResponseError = SessionError.Http(404, "Missing"),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    questionLiveStore = FakeQuestionLiveStore(listOf(request)),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                "que-1",
                viewModel.uiState.value.questionRequest
                    ?.id,
            )

            viewModel.toggleQuestionOption(request.id, request.sessionId, "A")
            viewModel.submitQuestion(request.id, request.sessionId)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.questionRequest)
            assertEquals(1, sessionRepository.pendingQuestionLoads)
            assertEquals("que-1", sessionRepository.repliedQuestionId)
        }

    @Test
    fun lateQuestionDismiss_usesRenderedRequestIdentity() =
        runTest(testDispatcher) {
            val first = questionRequest("que-1", "ses-1")
            val second = questionRequest("que-2", "child")
            val sessionRepository =
                FakeSessionRepository(
                    sessions =
                        listOf(
                            Session(id = "ses-1", title = "Title"),
                            Session(id = "child", title = "Child", parentId = "ses-1"),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    questionLiveStore = FakeQuestionLiveStore(listOf(first, second)),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.dismissQuestion(second.id, second.sessionId)
            advanceUntilIdle()

            assertEquals("que-2", sessionRepository.rejectedQuestionId)
        }

    @Test
    fun lateQuestionDismissFailure_doesNotPaintErrorOnVisibleRequest() =
        runTest(testDispatcher) {
            val first = questionRequest("que-1", "ses-1")
            val second = questionRequest("que-2", "child")
            val gate = CompletableDeferred<Unit>()
            val sessionRepository =
                FakeSessionRepository(
                    sessions =
                        listOf(
                            Session(id = "ses-1", title = "Title"),
                            Session(id = "child", title = "Child", parentId = "ses-1"),
                        ),
                    questionResponseError = SessionError.Http(500, "boom"),
                    questionHoldUntil = gate,
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    questionLiveStore = FakeQuestionLiveStore(listOf(first, second)),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                "que-1",
                viewModel.uiState.value.questionRequest
                    ?.id,
            )

            viewModel.dismissQuestion(second.id, second.sessionId)
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.questionSubmitting)
            assertEquals(
                "que-1",
                viewModel.uiState.value.questionRequest
                    ?.id,
            )

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("que-2", sessionRepository.rejectedQuestionId)
            assertEquals(
                "que-1",
                viewModel.uiState.value.questionRequest
                    ?.id,
            )
            assertEquals(null, viewModel.uiState.value.questionErrorMessage)
            assertEquals(false, viewModel.uiState.value.questionSubmitting)
        }

    @Test
    fun loadMessages_success() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(SessionChatTimelineState.Ready, state.timeline)
            assertEquals(1, state.messages.size)
            assertEquals("Hello", state.messages.first().text)
            assertNotNull(state.timelineFrame)
            assertEquals(1, state.timelineFrame?.rows?.size)
        }

    @Test
    fun composerChange_reusesTimelineFrameAndMessages() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val before = viewModel.uiState.value
            assertNotNull(before.timelineFrame)

            viewModel.updateComposerText("draft only")
            advanceUntilIdle()

            val after = viewModel.uiState.value
            assertEquals("draft only", after.composer.text)
            assertSame(before.timelineFrame, after.timelineFrame)
            assertSame(before.messages, after.messages)
            assertEquals(before.timeline, after.timeline)
        }

    @Test
    fun bindDoesNotFetchLastTurnReviewChangesDiff() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionDiffs =
                        listOf(
                            FileDiff(
                                path = "one.kt",
                                patch = null,
                                additions = 1,
                                deletions = 2,
                                status = FileDiffStatus.Modified,
                            ),
                            FileDiff(
                                path = "two.kt",
                                patch = null,
                                additions = 3,
                                deletions = 0,
                                status = FileDiffStatus.Added,
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(id = "user-1", role = MessageRole.User, text = "Change it", sentAtEpochMs = 1),
                                    Message(id = "assistant-1", role = MessageRole.Assistant, text = "Done", sentAtEpochMs = 2),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.reviewChangesSummary)
            assertEquals(null, sessionRepository.diffRequest)
        }

    @Test
    fun loadedChildSessionExposesParentWithoutNavigationParent() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    getSessionResult = Session(id = "ses-child", title = "Child", parentId = "ses-parent"),
                )
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)

            viewModel.bind("ses-child", "Child", "/repo")
            advanceUntilIdle()

            assertEquals("ses-parent", viewModel.uiState.value.sessionParentId)
        }

    @Test
    fun rootSessionHasNoParent() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = FakeSessionRepository())

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.sessionParentId)
        }

    @Test
    fun v2DoesNotLoadLastTurnReviewChangesSummary() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionDiffs =
                        listOf(
                            FileDiff(
                                path = "one.kt",
                                patch = null,
                                additions = 1,
                                deletions = 2,
                                status = FileDiffStatus.Modified,
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(id = "user-1", role = MessageRole.User, text = "Change it", sentAtEpochMs = 1),
                                    Message(id = "assistant-1", role = MessageRole.Assistant, text = "Done", sentAtEpochMs = 2),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.reviewChangesSummary)
            assertEquals(false, viewModel.uiState.value.supportsSessionMessageDiff)
            assertEquals(null, sessionRepository.diffRequest)
        }

    @Test
    fun newerLatestTurnCancelsStaleReviewChangesSummary() =
        runTest(testDispatcher) {
            val region = SessionChatReviewChanges(backgroundScope).apply { ioDispatcher = testDispatcher }
            region.bind("ses-1", "/repo")

            region.updateCandidate(
                messages = listOf(reviewChangesMessage("user-1")),
                hasResolvedSessionStatus = true,
                isTurnActive = false,
                isSending = false,
            )
            runCurrent()
            region.updateCandidate(
                messages = listOf(reviewChangesMessage("user-1"), reviewChangesMessage("user-2")),
                hasResolvedSessionStatus = true,
                isTurnActive = false,
                isSending = false,
            )
            runCurrent()

            assertEquals("user-2", region.state.value.messageId)
            assertEquals(null, region.state.value.summary)
        }

    @Test
    fun publishedLastTurnSummaryShowsPillWithoutBindFetch() =
        runTest(testDispatcher) {
            val store = LastTurnReviewChangesStore()
            val region =
                SessionChatReviewChanges(
                    backgroundScope,
                    lastTurnReviewChangesStore = store,
                ).apply { ioDispatcher = testDispatcher }
            region.bind("ses-1", "/repo")
            region.updateCandidate(
                messages = listOf(reviewChangesMessage("user-1")),
                hasResolvedSessionStatus = true,
                isTurnActive = false,
                isSending = false,
            )
            runCurrent()

            assertEquals(null, region.state.value.summary)

            store.publish(
                sessionId = "ses-1",
                messageId = "user-1",
                summary = ReviewChangesSummary(2, 4, 1, "user-1"),
            )
            runCurrent()

            assertEquals(ReviewChangesSummary(2, 4, 1, "user-1"), region.state.value.summary)
            assertEquals("user-1", region.state.value.messageId)
            assertTrue(ReviewChangesPillVisibility.shouldShow(region.state.value.summary))
        }

    @Test
    fun failedReviewChangesCountStaysHidden() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionDiffError = SessionError.Transport(IllegalStateException("offline")),
                )
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(id = "user-1", role = MessageRole.User, text = "Change it", sentAtEpochMs = 1),
                                    Message(id = "assistant-1", role = MessageRole.Assistant, text = "Done", sentAtEpochMs = 2),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.reviewChangesSummary)
            assertEquals(null, sessionRepository.diffRequest)
        }

    @Test
    fun activeLatestTurnDoesNotLoadReviewChangesCount() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(sessionStatusType = "busy")
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(id = "user-1", role = MessageRole.User, text = "Change it", sentAtEpochMs = 1),
                                    Message(id = "tool-1", role = MessageRole.Tool, text = "Still working", sentAtEpochMs = 2),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, sessionRepository.diffRequest)
            assertEquals(null, viewModel.uiState.value.reviewChangesSummary)
        }

    @Test
    fun unresolvedStatusDoesNotLoadReviewChangesCount() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(sessionStatusError = SessionError.Transport(IllegalStateException("offline")))
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(id = "user-1", role = MessageRole.User, text = "Change it", sentAtEpochMs = 1),
                                    Message(id = "assistant-1", role = MessageRole.Assistant, text = "Partial", sentAtEpochMs = 2),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, sessionRepository.diffRequest)
            assertEquals(null, viewModel.uiState.value.reviewChangesSummary)
        }

    @Test
    fun loadMessages_enrichesMarkdownBeforeReady() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.Assistant,
                                    text = "# Heading",
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(SessionChatTimelineState.Ready, state.timeline)
            assertNotNull(state.messages.single().renderedMarkdown)
        }

    @Ignore("Quarantined from CI: stress println benchmark, not a correctness gate")
    @Test
    fun benchmark_loadMessages_longMarkdownHistory() =
        runTest(testDispatcher) {
            val messages = longMarkdownHistory()
            val highlighter = OpenCodeBundledJoniMarkdownHighlighter.default()
            val detectorMs = measureMs { messages.count { OpenCodeMarkdownDetector.shouldRender(it.text) } }
            val parserMs =
                measureMs {
                    messages.forEach { message ->
                        if (OpenCodeMarkdownDetector.shouldRender(message.text)) {
                            OpenCodeMarkdownParser.parse(message.text, highlighter)
                        }
                    }
                }
            val preparer =
                MessageContentPreparer(OpenCodeTextMateHighlightingEngine(highlighter)).apply {
                    computationDispatcher = testDispatcher
                }
            val preparerMs =
                measureMs {
                    preparer.prepareMessages(messages)
                    Unit
                }
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages = messages,
                            contentPreparer = preparer,
                        ),
                )

            val startedAt = System.nanoTime()
            viewModel.bind("ses-benchmark", "Markdown benchmark", "/repo")
            advanceUntilIdle()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals(SessionChatTimelineState.Ready, viewModel.uiState.value.timeline)
            assertEquals(20, viewModel.uiState.value.messages.size)
            println(
                "MARKDOWN_BENCHMARK android detector-ms=$detectorMs parser-ms=$parserMs " +
                    "preparer-ms=$preparerMs viewmodel-load-ms=$elapsedMs messages=20 code-lines=3000",
            )
        }

    @Test
    fun loadMessages_empty() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository(messages = emptyList()))
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(SessionChatTimelineState.Empty, viewModel.uiState.value.timeline)
        }

    @Test
    fun loadMessages_empty_restoresCachedModelSelection() =
        runTest(testDispatcher) {
            val defaultOption =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = listOf("low", "high"),
                    contextLimitTokens = 128_000,
                )
            val cachedOption =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-4",
                    modelName = "GPT-4",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val selectionStore = FakeSessionModelSelectionStore()
            selectionStore.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection =
                    SessionModelSelection(
                        providerId = "openai",
                        modelId = "gpt-4",
                    ),
            )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(messages = emptyList()),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(
                                options = listOf(defaultOption, cachedOption),
                                serverDefault = defaultOption,
                            ),
                        ),
                    sessionModelSelectionStore = selectionStore,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(SessionChatTimelineState.Empty, viewModel.uiState.value.timeline)
            assertEquals(
                "openai",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "gpt-4",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun bind_resetsUiStateBeforeRegionsFinish() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.User,
                                    text = "Hello",
                                    sentAtEpochMs = 1_000L,
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "First", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("draft text")

            viewModel.bind("ses-2", "Second", "/repo")

            assertEquals("ses-2", viewModel.uiState.value.sessionId)
            assertEquals("Second", viewModel.uiState.value.sessionTitle)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertEquals(SessionChatTimelineState.Loading, viewModel.uiState.value.timeline)
        }

    @Test
    fun openAndDismissActivityCard_resetOnSessionChange() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "First", "/repo")
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)

            // A card that is not in the timeline closes at once.
            viewModel.openActivityCard("ac:gone")
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)
        }

    @Test
    fun presentedActivityCard_observesItsRunningChild_andClosesWhenTheCardLeaves() =
        runTest(testDispatcher) {
            SessionChatDisplayPreferences.updateTimelineStyle(SessionChatTimelineStyle.ActivityCard)
            val task =
                MessagePart.Tool(
                    toolName = "task",
                    status = ToolStatus.Running,
                    id = "p1",
                    input =
                        buildJsonObject {
                            put("subagent_type", "explore")
                            put("description", "Find the pill")
                        },
                    metadata = buildJsonObject { put("sessionId", "ses-child") },
                )
            val assistant = Message(id = "msg-2", role = MessageRole.Assistant, text = "", parts = listOf(task))
            val repository = FakeMessageRepository()
            repository.messages = repository.messages + assistant
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "First", "/repo")
            advanceUntilIdle()

            viewModel.openActivityCard("ac:p1")
            advanceUntilIdle()
            assertEquals("ac:p1", viewModel.uiState.value.presentedActivityCard)
            // The fake serves the same transcript for the child: its one visible tool step.
            assertEquals(1, viewModel.uiState.value.activityCardChildren["ses-child"]?.toolCount)

            viewModel.dismissActivityCard()
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)

            viewModel.openActivityCard("ac:p1")
            repository.messages = repository.messages.filter { it.id != "msg-2" }
            viewModel.refreshMessages()
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)

            viewModel.openActivityCard("ac:p1")
            viewModel.bind("ses-2", "Second", "/repo")
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)
            assertTrue(viewModel.uiState.value.activityCardChildren.isEmpty())
        }

    @Test
    fun activityCardStep_opensAndClosesInTheSheet_andClearsWithTheCardOrStep() =
        runTest(testDispatcher) {
            SessionChatDisplayPreferences.updateTimelineStyle(SessionChatTimelineStyle.ActivityCard)
            val read = MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "p1", output = "file body")
            val shell = MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p2", output = "ok")
            val grep = MessagePart.Tool(toolName = "grep", status = ToolStatus.Completed, id = "p3", output = "match")
            val assistant = Message(id = "msg-2", role = MessageRole.Assistant, text = "", parts = listOf(read, shell, grep))
            val repository = FakeMessageRepository()
            repository.messages = repository.messages + assistant
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "First", "/repo")
            advanceUntilIdle()

            // No sheet, no detail.
            viewModel.openActivityCardStep("p2")
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)

            viewModel.openActivityCard("ac:p1")
            // A step that is not on the card is ignored.
            viewModel.openActivityCardStep("nope")
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)
            viewModel.openActivityCardStep("p2")
            assertEquals("p2", viewModel.uiState.value.activityCardDetailStepId)
            assertEquals("ac:p1", viewModel.uiState.value.presentedActivityCard)

            // Back returns to the list; the sheet stays open.
            viewModel.closeActivityCardStep()
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)
            assertEquals("ac:p1", viewModel.uiState.value.presentedActivityCard)

            // Dismiss clears both.
            viewModel.openActivityCardStep("p2")
            viewModel.dismissActivityCard()
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)

            // Reopening a card starts at its list.
            viewModel.openActivityCard("ac:p1")
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)

            // The step leaving the card goes back to the list. Two steps stay, so it is still a card.
            viewModel.openActivityCardStep("p2")
            repository.messages = repository.messages.map { if (it.id == "msg-2") it.copy(parts = listOf(read, grep)) else it }
            viewModel.refreshMessages()
            advanceUntilIdle()
            assertEquals("ac:p1", viewModel.uiState.value.presentedActivityCard)
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)

            // The card leaving closes the sheet and the detail.
            viewModel.openActivityCardStep("p1")
            assertEquals("p1", viewModel.uiState.value.activityCardDetailStepId)
            repository.messages = repository.messages.filter { it.id != "msg-2" }
            viewModel.refreshMessages()
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.presentedActivityCard)
            assertEquals(null, viewModel.uiState.value.activityCardDetailStepId)
        }

    @Test
    fun expandStepParagraph_survivesRepublish_sameSessionRebind_andResetsOnSessionChange() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "First", "/repo")
            advanceUntilIdle()

            viewModel.expandStepParagraph("s:p1", chipCount = 5)
            // A tap with fewer chips than already revealed never shrinks the expansion.
            viewModel.expandStepParagraph("s:p1", chipCount = 4)
            assertEquals(mapOf("s:p1" to 5), viewModel.uiState.value.expandedStepParagraphs)
            // A later tap on the grown paragraph reveals the new chips too.
            viewModel.expandStepParagraph("s:p1", chipCount = 9)
            assertEquals(mapOf("s:p1" to 9), viewModel.uiState.value.expandedStepParagraphs)

            // Region republishes and a same-session rebind keep the expansion.
            viewModel.updateComposerText("draft")
            viewModel.bind("ses-1", "First", "/repo")
            advanceUntilIdle()
            assertEquals(mapOf("s:p1" to 9), viewModel.uiState.value.expandedStepParagraphs)

            viewModel.bind("ses-2", "Second", "/repo")
            assertEquals(emptyMap<String, Int>(), viewModel.uiState.value.expandedStepParagraphs)
            advanceUntilIdle()
            assertEquals(emptyMap<String, Int>(), viewModel.uiState.value.expandedStepParagraphs)
        }

    @Test
    fun composerDraft_restoresTextAndMentionsForSameSessionOnly() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val first = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            first.updateComposerText("@", cursor = 1)
            first.selectAtSuggestion("explore")
            advanceTimeBy(300)
            advanceUntilIdle()

            val other = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            other.bind("ses-2", "Other", "/repo")
            advanceUntilIdle()
            assertEquals("", other.uiState.value.composer.text)

            val restored = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            restored.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals("@explore ", restored.uiState.value.composer.text)
            assertEquals(
                listOf("explore"),
                restored.uiState.value.composer.agentMentions
                    .map { it.name },
            )
        }

    @Test
    fun composerDraft_successfulSendClearsStoredDraft() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val first = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            first.updateComposerText("send me")
            first.sendPrompt()
            advanceUntilIdle()
            assertEquals("", first.uiState.value.composer.text)

            val restored = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            restored.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals("", restored.uiState.value.composer.text)
        }

    @Test
    fun composerDraft_promoteOptimisticCreatePersistsUnderRealSessionId() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val sessionRepository =
                FakeSessionRepository(
                    createSessionResult = Session(id = "ses-new", title = "Created", directory = "/repo"),
                )
            val first =
                createViewModel(
                    FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    composerDraftStore = draftStore,
                )
            first.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, "New Session", "/repo")
            first.updateComposerText("typed during create")
            advanceUntilIdle()
            assertEquals("typed during create", first.uiState.value.composer.text)
            assertEquals("ses-new", first.uiState.value.sessionId)

            val restored = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            restored.bind("ses-new", "Created", "/repo")
            advanceUntilIdle()
            assertEquals("typed during create", restored.uiState.value.composer.text)
            assertNull(
                draftStore.load(
                    dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity(
                        "http://127.0.0.1:4096/",
                        "opencode",
                    ),
                    "/repo",
                    OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                ),
            )
        }

    @Test
    fun bind_sameSessionReuse_reassertsReadyFocus() =
        runTest(testDispatcher) {
            val readyStore = RecordingSessionReadyStore()
            val viewModel = createViewModel(FakeMessageRepository(), sessionReadyStore = readyStore)

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(listOf("ses-1" to "/repo"), readyStore.focusCalls)

            // Re-entering the same session+worktree after dispose cleared focus must still
            // re-assert Ready focus so opening the session clears Ready.
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(2, readyStore.focusCalls.size)
            assertEquals("ses-1" to "/repo", readyStore.focusCalls.last())
        }

    @Test
    fun clearSessionReadyFocus_releasesOnlyTheBoundSession() =
        runTest(testDispatcher) {
            val readyStore = RecordingSessionReadyStore()
            val viewModel = createViewModel(FakeMessageRepository(), sessionReadyStore = readyStore)

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.clearSessionReadyFocus()

            assertEquals(listOf("ses-1"), readyStore.releaseCalls)
            assertTrue(readyStore.focusCalls.none { it.first == null })
        }

    @Test
    fun sessionViewReporter_followsChatFocus() =
        runTest(testDispatcher) {
            val reporter = RecordingSessionViewReporter()
            val viewModel = createViewModel(FakeMessageRepository(), sessionViewReporter = reporter)

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.clearSessionReadyFocus()
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(listOf("ses-1", "ses-1"), reporter.focusCalls)
            assertEquals(listOf("ses-1"), reporter.releaseCalls)
        }

    @Test
    fun sessionViewReporter_focusesPromotedOptimisticSession() =
        runTest(testDispatcher) {
            val reporter = RecordingSessionViewReporter()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository =
                        FakeSessionRepository(
                            createSessionResult = Session(id = "ses-new", title = "Created", directory = "/repo"),
                        ),
                    sessionViewReporter = reporter,
                )

            viewModel.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, "New Session", "/repo")
            advanceUntilIdle()
            viewModel.clearSessionReadyFocus()

            assertEquals(listOf("ses-new"), reporter.focusCalls)
            assertEquals(listOf("ses-new"), reporter.releaseCalls)
        }

    // Project identity (#783): Home loaded the main checkout's inventory, which lists /wt/tax.
    private fun homeInventoryCache(): WorktreeInventoryCache =
        WorktreeInventoryCache().apply {
            setAddedPaths(listOf("/repo"), HARNESS_BASE_URL)
            record(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), "/repo", HARNESS_BASE_URL)
        }

    private fun identity(state: SessionChatUiState) =
        SessionChatProjectIdentity(
            label = state.projectLabel,
            letter = state.projectLetter,
            accent = state.projectLetterAccent,
            isWorktreeCheckout = state.worktreeCheckout,
        )

    @Test
    fun bind_worktreeSession_namesItsCheckoutOnTheFirstFrame() =
        runTest(testDispatcher) {
            val worktrees = GatedWorktreeRepository(WorktreeLoad.Loaded(listOf("/repo", "/wt/tax"), listsSessions = false))
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    connectionProfileStore = FakeConnectionProfileStore(worktrees = mutableListOf("/repo")),
                    worktreeRepository = worktrees,
                    worktreeInventoryCache = homeInventoryCache(),
                )

            viewModel.bind("ses-1", "Title", "/wt/tax")
            val first = viewModel.uiState.value
            assertTrue(first.worktreeCheckout)
            assertEquals("tax", first.projectLabel)
            assertNull(first.projectLetter)
            assertNull(first.projectLetterAccent)

            // The server agrees: nothing changes once the refine lands.
            worktrees.open()
            advanceUntilIdle()
            assertEquals(1, worktrees.answered)
            assertEquals(identity(first), identity(viewModel.uiState.value))
        }

    @Test
    fun bind_mainCheckoutSession_keepsItsLetterFromTheFirstFrame() =
        runTest(testDispatcher) {
            val worktrees = GatedWorktreeRepository(WorktreeLoad.Loaded(listOf("/repo", "/wt/tax"), listsSessions = false))
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    connectionProfileStore = FakeConnectionProfileStore(worktrees = mutableListOf("/repo")),
                    worktreeRepository = worktrees,
                    worktreeInventoryCache = homeInventoryCache(),
                )

            viewModel.bind("ses-1", "Title", "/repo")
            val first = viewModel.uiState.value
            assertFalse(first.worktreeCheckout)
            assertEquals("repo", first.projectLabel)
            assertEquals('R', first.projectLetter)
            assertEquals(letterAccentForWorktree("/repo"), first.projectLetterAccent)

            worktrees.open()
            advanceUntilIdle()
            assertEquals(1, worktrees.answered)
            assertEquals(identity(first), identity(viewModel.uiState.value))
        }

    @Test
    fun bind_worktreeSession_failedRefineKeepsTheCheckout() =
        runTest(testDispatcher) {
            val worktrees = GatedWorktreeRepository(WorktreeLoad.Failed(IllegalStateException("offline")))
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    connectionProfileStore = FakeConnectionProfileStore(worktrees = mutableListOf("/repo")),
                    worktreeRepository = worktrees,
                    worktreeInventoryCache = homeInventoryCache(),
                )

            viewModel.bind("ses-1", "Title", "/wt/tax")
            worktrees.open()
            advanceUntilIdle()

            assertEquals(1, worktrees.answered)
            assertTrue(viewModel.uiState.value.worktreeCheckout)
            assertEquals("tax", viewModel.uiState.value.projectLabel)
        }

    @Test
    fun bind_worktreeSession_coldOpenResolvesAndSharesTheInventoryForTheNextOpen() =
        runTest(testDispatcher) {
            val cache = WorktreeInventoryCache()
            val worktrees = GatedWorktreeRepository(WorktreeLoad.Loaded(listOf("/repo", "/wt/tax"), listsSessions = false))
            val store = FakeConnectionProfileStore(worktrees = mutableListOf("/repo"))
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    connectionProfileStore = store,
                    worktreeRepository = worktrees,
                    worktreeInventoryCache = cache,
                )

            // Nothing loaded yet (Home never ran): the folder with its letter until the server answers.
            viewModel.bind("ses-1", "Title", "/wt/tax")
            assertFalse(viewModel.uiState.value.worktreeCheckout)
            assertEquals("tax", viewModel.uiState.value.projectLabel)
            worktrees.open()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.worktreeCheckout)

            val reopened =
                createViewModel(
                    FakeMessageRepository(),
                    connectionProfileStore = store,
                    worktreeRepository = worktrees,
                    worktreeInventoryCache = cache,
                )
            reopened.bind("ses-1", "Title", "/wt/tax")
            assertTrue(reopened.uiState.value.worktreeCheckout)
            assertEquals("tax", reopened.uiState.value.projectLabel)
        }

    @Test
    fun bind_sameSessionReuse_refinesProjectLabelWhenPeersChange() =
        runTest(testDispatcher) {
            val connectionProfileStore =
                FakeConnectionProfileStore(
                    active =
                        ConnectionProfile(
                            baseUrl = "http://127.0.0.1:4096/",
                            username = "opencode",
                            password = "",
                        ),
                    worktrees = mutableListOf("/Users/example/Projects/opencode"),
                )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    connectionProfileStore = connectionProfileStore,
                )

            viewModel.bind("ses-1", "Title", "/Users/example/Projects/opencode")
            advanceUntilIdle()
            assertEquals("opencode", viewModel.uiState.value.projectLabel)

            connectionProfileStore.worktrees += "/Users/example/Documents/opencode"
            viewModel.bind("ses-1", "Title", "/Users/example/Projects/opencode")
            advanceUntilIdle()

            assertEquals("..Projects/opencode", viewModel.uiState.value.projectLabel)
        }

    @Test
    fun bind_sameSessionReuse_resubscribesLiveMessages() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                listOf("Hello"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )

            repository.messages =
                listOf(
                    Message(
                        id = "msg-2",
                        role = MessageRole.User,
                        text = "After reconnect",
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                listOf("After reconnect"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )
        }

    @Test
    fun openRenameSession_prefillsCurrentTitle() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()

            val rename = viewModel.uiState.value.rename
            assertTrue(rename.isDialogVisible)
            assertEquals("Original title", rename.draftTitle)
            assertEquals(null, rename.errorMessage)
            assertFalse(rename.isSubmitting)
        }

    @Test
    fun confirmRenameSession_emptyTitle_keepsDialogWithoutApiCall() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()
            viewModel.updateRenameDraft("   ")
            advanceUntilIdle()
            viewModel.confirmRenameSession()
            advanceUntilIdle()

            assertEquals(null, sessionRepository.updateRequest)
            assertTrue(viewModel.uiState.value.rename.isDialogVisible)
            assertEquals("Enter a session title", viewModel.uiState.value.rename.errorMessage)
            assertEquals("Original title", viewModel.uiState.value.sessionTitle)
        }

    @Test
    fun confirmRenameSession_unchangedTitle_dismissesWithoutApiCall() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()
            viewModel.updateRenameDraft("  Original title  ")
            advanceUntilIdle()
            viewModel.confirmRenameSession()
            advanceUntilIdle()

            assertEquals(null, sessionRepository.updateRequest)
            assertFalse(viewModel.uiState.value.rename.isDialogVisible)
            assertEquals("Original title", viewModel.uiState.value.sessionTitle)
        }

    @Test
    fun confirmRenameSession_success_updatesTitleAndDismisses() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()
            viewModel.updateRenameDraft("Renamed title")
            advanceUntilIdle()
            viewModel.confirmRenameSession()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.UpdateRequest("ses-1", "Renamed title", "/repo"),
                sessionRepository.updateRequest,
            )
            assertFalse(viewModel.uiState.value.rename.isDialogVisible)
            assertEquals("Renamed title", viewModel.uiState.value.sessionTitle)
            assertEquals("ses-1", viewModel.uiState.value.sessionId)
        }

    @Test
    fun confirmRenameSession_failure_keepsPreviousTitleAndShowsError() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(updateSessionError = SessionError.Http(500, "boom"))
            val viewModel = createViewModel(FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()
            viewModel.updateRenameDraft("Renamed title")
            advanceUntilIdle()
            viewModel.confirmRenameSession()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.rename.isDialogVisible)
            assertFalse(viewModel.uiState.value.rename.isSubmitting)
            assertEquals("boom", viewModel.uiState.value.rename.errorMessage)
            assertEquals("Original title", viewModel.uiState.value.sessionTitle)
        }

    @Test
    fun sessionUpdatedEvent_appliesTitleForOpenSession() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.updated",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "info",
                                buildJsonObject {
                                    put("id", "ses-1")
                                    put("title", "Live title")
                                },
                            )
                        },
                ),
            )
            advanceUntilIdle()

            assertEquals("Live title", viewModel.uiState.value.sessionTitle)
        }

    @Test
    fun sessionDeletedEvent_leavesOpenSessionOnly() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            fun deleted(id: String) =
                OpencodeEvent(
                    type = "session.deleted",
                    rawProperties = buildJsonObject { put("info", buildJsonObject { put("id", id) }) },
                )
            repository.eventStream.emit(deleted("ses-other"))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.shouldLeaveSession)

            repository.eventStream.emit(deleted("ses-1"))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.shouldLeaveSession)
        }

    @Test
    fun cancelRenameSession_hidesDialog() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()
            viewModel.updateRenameDraft("Draft")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.rename.isDialogVisible)
            viewModel.cancelRenameSession()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.rename.isDialogVisible)
            assertEquals("Original title", viewModel.uiState.value.sessionTitle)
        }

    @Test
    fun bind_sameSessionKeepsRenamedTitleWhenNavTitleIsStale() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            viewModel.openRenameSession()
            advanceUntilIdle()
            viewModel.updateRenameDraft("Renamed title")
            advanceUntilIdle()
            viewModel.confirmRenameSession()
            advanceUntilIdle()
            assertEquals("Renamed title", viewModel.uiState.value.sessionTitle)

            // Simulate configuration change re-running LaunchedEffect with stale nav title.
            viewModel.bind("ses-1", "Original title", "/repo")
            advanceUntilIdle()

            assertEquals("Renamed title", viewModel.uiState.value.sessionTitle)
        }

    @Test
    fun loadMessages_error() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository(shouldFailLoad = true))
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(SessionChatTimelineState.Error, viewModel.uiState.value.timeline)
        }

    @Test
    fun catchUpChrome_showsSyncingPill() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.syncingIndicatorLabel)

            repository.catchUpChrome.value = true
            advanceUntilIdle()

            assertEquals(SessionMessageSyncChrome.STATUS_LABEL, viewModel.uiState.value.syncingIndicatorLabel)

            repository.catchUpChrome.value = false
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.syncingIndicatorLabel)
        }

    @Test
    fun quietRefresh_recoversFromErrorWhenMessagesArrive() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(shouldFailLoad = true)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(SessionChatTimelineState.Error, viewModel.uiState.value.timeline)

            repository.shouldFailLoad = false
            repository.eventStream.emit(
                OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"),
            )
            advanceTimeBy(301)
            advanceUntilIdle()

            assertEquals(SessionChatTimelineState.Ready, viewModel.uiState.value.timeline)
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun sendPrompt_matchingServerCommand_runsCommandApi() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository = FakeCommandRepository()
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/in")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.slashSuggestionsVisible)
            assertTrue(
                viewModel.uiState.value.slashSuggestions
                    .any { it.commandName == "init" },
            )
            viewModel.selectSlashSuggestion("init")
            runCurrent()
            assertEquals("/init ", viewModel.uiState.value.composer.text)
            assertFalse(viewModel.uiState.value.slashSuggestionsVisible)
            viewModel.updateComposerText("/init please")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(commandRepository.runCommandCalled)
            assertEquals("init", commandRepository.lastRunOptions?.command)
            assertEquals("please", commandRepository.lastRunOptions?.arguments)
            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals("", viewModel.uiState.value.composer.text)
        }

    @Test
    fun sendPrompt_serverCommandHttpFailure_showsServerMessage() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository =
                FakeCommandRepository(runError = CommandError.Http(400, "parts.0.type Required"))
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/init please")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(commandRepository.runCommandCalled)
            assertEquals(
                "parts.0.type Required",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
        }

    @Test
    fun sendPrompt_serverCommandTransportFailure_showsReachabilityError() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository =
                FakeCommandRepository(runError = CommandError.Transport(RuntimeException("boom")))
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/init please")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(
                "Unable to reach the server",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
        }

    @Test
    fun sendPrompt_skillSlash_sendsPromptWithSkillId() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository =
                FakeCommandRepository(
                    commands =
                        listOf(
                            ServerCommand(name = "init", source = ServerCommandSource.Command),
                            ServerCommand(
                                name = "OpenCode",
                                description = "Ask about OpenCode",
                                source = ServerCommandSource.Skill,
                                skillId = "opencode",
                            ),
                        ),
                )
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/OpenCode check the error")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertFalse(commandRepository.runCommandCalled)
            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("/OpenCode check the error", repository.sentOptions?.text)
            val skill = repository.sentOptions?.skillMentions?.single()
            assertEquals("opencode", skill?.skillId)
            assertEquals(0, skill?.start)
            assertEquals(9, skill?.end)
            assertEquals("", viewModel.uiState.value.composer.text)
        }

    @Test
    fun sendPrompt_multipleInlineSkills_sendsTextVerbatimWithOffsets() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository = FakeCommandRepository(commands = skillCatalog)
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("fix @", cursor = 5)
            advanceUntilIdle()
            viewModel.selectAtSuggestion("explore")
            runCurrent()
            assertEquals("fix @explore ", viewModel.uiState.value.composer.text)

            viewModel.updateComposerText("fix @explore /td", cursor = 16)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.slashSuggestionsVisible)
            val offered = viewModel.uiState.value.slashSuggestions
            assertEquals(listOf("tdd"), offered.map { it.commandName })
            viewModel.selectSlashSuggestion("tdd")
            runCurrent()
            assertEquals("fix @explore /tdd ", viewModel.uiState.value.composer.text)

            val next = "fix @explore /tdd the login flow /sec"
            viewModel.updateComposerText(next, cursor = next.length)
            advanceUntilIdle()
            viewModel.selectSlashSuggestion("security-check")
            runCurrent()
            val draft = "fix @explore /tdd the login flow /security-check "
            assertEquals(draft, viewModel.uiState.value.composer.text)
            assertEquals(
                listOf(4 until 12, 13 until 17, 33 until 48),
                viewModel.uiState.value.composer.mentionRanges,
            )

            viewModel.sendPrompt()
            advanceUntilIdle()

            assertFalse(commandRepository.runCommandCalled)
            assertEquals(draft.trim(), repository.sentOptions?.text)
            val sent = repository.sentOptions
            assertEquals("explore", sent?.agentMentions?.single()?.name)
            assertEquals(
                listOf("tdd" to (13 to 17), "security-check" to (33 to 48)),
                sent?.skillMentions?.map { it.skillId to (it.start to it.end) },
            )
        }

    @Test
    fun selectSlashSuggestion_midDraft_keepsRestOfDraft() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    commandRepository = FakeCommandRepository(commands = skillCatalog),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("please /td the login flow", cursor = 10)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.slashSuggestionsVisible)
            // Commands are only offered for a leading slash; skills go anywhere.
            val offered = viewModel.uiState.value.slashSuggestions
            assertFalse(offered.any { it.commandName == "init" })
            viewModel.selectSlashSuggestion("tdd")
            runCurrent()

            val composer = viewModel.uiState.value.composer
            assertEquals("please /tdd  the login flow", composer.text)
            assertEquals(12, composer.cursor)
            assertEquals(7, composer.skillMentions.single().start)
            assertFalse(viewModel.uiState.value.slashSuggestionsVisible)
        }

    @Test
    fun slashSuggestions_omitSkillsAlreadyInDraft() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    commandRepository = FakeCommandRepository(commands = skillCatalog),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/tdd", cursor = 4)
            advanceUntilIdle()
            viewModel.selectSlashSuggestion("tdd")
            runCurrent()
            viewModel.updateComposerText("/tdd /", cursor = 6)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.slashSuggestionsVisible)
            assertEquals(listOf("security-check"), state.slashSuggestions.map { it.commandName })
        }

    @Test
    fun sendPrompt_unknownSlash_fallsThroughToPrompt() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository = FakeCommandRepository()
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/not-a-command hi")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertFalse(commandRepository.runCommandCalled)
            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("/not-a-command hi", repository.sentOptions?.text)
        }

    @Test
    fun atSuggestion_selectInsertsMentionAndSendsAgentParts() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository = repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("@", cursor = 1)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.atSuggestionsVisible)
            assertTrue(
                viewModel.uiState.value.atSuggestions
                    .any { it.agentName == "explore" },
            )

            viewModel.selectAtSuggestion("explore")
            runCurrent()
            assertEquals("@explore ", viewModel.uiState.value.composer.text)
            assertEquals(1, viewModel.uiState.value.composer.agentMentions.size)
            assertFalse(viewModel.uiState.value.atSuggestionsVisible)

            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("@explore", repository.sentOptions?.text)
            assertEquals(1, repository.sentOptions?.agentMentions?.size)
            assertEquals(
                "explore",
                repository.sentOptions
                    ?.agentMentions
                    ?.single()
                    ?.name,
            )
            assertEquals("", viewModel.uiState.value.composer.text)
            assertTrue(
                viewModel.uiState.value.composer.agentMentions
                    .isEmpty(),
            )
        }

    @Test
    fun atSuggestion_winsOverSlashSuggestions() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(repository = FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/init @", cursor = 7)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.atSuggestionsVisible)
            assertFalse(viewModel.uiState.value.slashSuggestionsVisible)
        }

    @Test
    fun sendPrompt_serverCommand_doesNotAttachMentionParts() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val commandRepository = FakeCommandRepository()
            val viewModel = createViewModel(repository = repository, commandRepository = commandRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/init @", cursor = 7)
            advanceUntilIdle()
            viewModel.selectAtSuggestion("explore")
            runCurrent()
            assertEquals("/init @explore ", viewModel.uiState.value.composer.text)
            assertEquals(1, viewModel.uiState.value.composer.agentMentions.size)
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(commandRepository.runCommandCalled)
            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals("init", commandRepository.lastRunOptions?.command)
            assertEquals("@explore", commandRepository.lastRunOptions?.arguments)
        }

    @Test
    fun atSuggestion_selectsProjectFileAndSendsReferenceNotAttachment() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val files =
                object : dev.opencode.mobile.domain.filesystem.ProjectFilesRepository {
                    override suspend fun listEntries(
                        worktree: String,
                        relativeDirectory: String,
                    ) = emptyList<dev.opencode.mobile.domain.filesystem.ProjectEntry>()

                    override suspend fun findFiles(
                        worktree: String,
                        query: String,
                        limit: Int,
                    ) = listOf(dev.opencode.mobile.domain.filesystem.ProjectEntry("App.kt", "src/App.kt", isDirectory = false))

                    override suspend fun readFile(
                        worktree: String,
                        relativePath: String,
                    ): dev.opencode.mobile.domain.filesystem.ProjectFileContent =
                        throw dev.opencode.mobile.domain.filesystem.ProjectFilesException("unused", detail = "unused")
                }
            val viewModel =
                createViewModel(repository = repository, projectFilesRepository = files).apply {
                    fileSearchDelayMs = 0
                }
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("see @App", cursor = 8)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.atSuggestions.any { it.filePath == "src/App.kt" })
            viewModel.selectAtSuggestion("file:src/App.kt")
            runCurrent()
            assertEquals("see @src/App.kt ", viewModel.uiState.value.composer.text)
            assertEquals(1, viewModel.uiState.value.composer.fileMentions.size)
            assertTrue(viewModel.uiState.value.composer.agentMentions.isEmpty())
            assertTrue(viewModel.uiState.value.composer.attachments.isEmpty())

            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals("see @src/App.kt", repository.sentOptions?.text)
            assertEquals("src/App.kt", repository.sentOptions?.fileMentions?.single()?.path)
            assertTrue(repository.sentOptions?.attachments.orEmpty().isEmpty())
            assertEquals("/repo", repository.sentOptions?.worktree)
        }

    @Test
    fun sendPrompt_success_reloadsMessages() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("New prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isSending)
            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("anthropic", repository.sentOptions?.model?.providerId)
            assertEquals("claude-sonnet", repository.sentOptions?.model?.modelId)
            assertEquals(null, repository.sentOptions?.variant)
            assertEquals("build", repository.sentOptions?.mode)
            assertEquals(
                listOf("Hello", "New prompt"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == "New prompt" && it.isPending },
            )
        }

    @Test
    fun sendPrompt_postSendReloadMatchingUser_dropsOptimistic() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(echoSentPrompt = true)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("New prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isSending)
            assertEquals(
                listOf("Hello", "New prompt"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.isPending },
            )
        }

    @Test
    fun sendPrompt_attachmentInFlight_showsSendingPercent() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdSendUntil = sendGate)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.addAttachments(listOf(testAttachment()))
            viewModel.sendPrompt()
            runCurrent()
            assertEquals("Sending 25%", viewModel.uiState.value.workingIndicatorLabel)
            sendGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(
                viewModel.uiState.value.workingIndicatorLabel
                    .orEmpty()
                    .startsWith("Sending"),
            )
        }

    @Test
    fun removeAttachment_deletesOwnedPendingFile() =
        runTest(testDispatcher) {
            val directory =
                java.io
                    .File(System.getProperty("java.io.tmpdir"), AttachmentLimits.STORE_DIRECTORY_NAME)
                    .apply { mkdirs() }
            val file = java.io.File(directory, "pending-remove.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.addAttachments(listOf(testAttachment().copy(localPath = file.absolutePath)))
            runCurrent()
            viewModel.removeAttachment(testAttachment().id)
            runCurrent()
            assertFalse(file.exists())
        }

    @Test
    fun attachmentOnlyPrompt_sendsAndCreatesOptimisticAttachmentMessage() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdSendUntil = sendGate)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val attachment = testAttachment()

            viewModel.addAttachments(listOf(attachment))
            runCurrent()
            assertTrue(viewModel.uiState.value.composer.canSend)
            viewModel.sendPrompt()
            runCurrent()

            assertEquals(listOf(attachment), repository.sentOptions?.attachments)
            assertEquals("", repository.sentOptions?.text)
            assertTrue(
                viewModel.uiState.value.messages.any { message ->
                    message.attachments.singleOrNull()?.filename == "photo.png"
                },
            )
            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
            sendGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
        }

    @Test
    fun attach_disabledForTextOnlyModelAndStripsUnsupportedPendingAttachments() =
        runTest(testDispatcher) {
            val textOnly =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "text-only",
                    modelName = "Text Only",
                    supportsImageInput = false,
                    supportsPdfInput = false,
                )
            val vision =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "vision",
                    modelName = "Vision",
                    supportsImageInput = true,
                    supportsPdfInput = false,
                )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(options = listOf(vision, textOnly), serverDefault = vision),
                        ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.composer.canAttach)
            assertTrue(viewModel.uiState.value.composer.canAttachPhotos)

            val image = testAttachment(id = "img", filename = "shot.png", kind = AttachmentKind.Image, mime = "image/png")
            val pdf = testAttachment(id = "pdf", filename = "brief.pdf", kind = AttachmentKind.Pdf, mime = "application/pdf")
            val text = testAttachment(id = "txt", filename = "notes.txt", kind = AttachmentKind.Text, mime = "text/plain")
            viewModel.addAttachments(listOf(image, pdf, text))
            runCurrent()
            assertEquals(
                listOf("img", "txt"),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
            assertEquals(
                "This model does not support PDF attachments.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)

            viewModel.selectModel(textOnly)
            runCurrent()
            assertFalse(viewModel.uiState.value.composer.canAttach)
            assertEquals(
                listOf("txt"),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
        }

    @Test
    fun failedAttachmentPrompt_restoresDraftAndAttachmentsWithoutOverwritingNewEdits() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(shouldFailSend = true, holdSendUntil = sendGate)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val original = testAttachment()

            viewModel.updateComposerText("Original")
            viewModel.addAttachments(listOf(original))
            runCurrent()
            viewModel.sendPrompt()
            runCurrent()
            viewModel.updateComposerText("New edit")
            val newer = testAttachment(id = "attachment-2", filename = "new.txt", kind = AttachmentKind.Text)
            viewModel.addAttachments(listOf(newer))
            sendGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("New edit", viewModel.uiState.value.composer.text)
            assertEquals(
                listOf(original.id, newer.id),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
            assertEquals(
                "send failed",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun sendPrompt_clearsComposerWhileRequestInFlight() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdSendUntil = sendGate)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/publish-prototype")
            viewModel.sendPrompt()
            runCurrent()

            assertEquals("", viewModel.uiState.value.composer.text)
            sendGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("", viewModel.uiState.value.composer.text)
        }

    @Test
    fun sendPrompt_serverCommand_clearsComposerWhileRequestInFlight() =
        runTest(testDispatcher) {
            val commandGate = CompletableDeferred<Unit>()
            val commandRepository = FakeCommandRepository(holdRunUntil = commandGate)
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    commandRepository = commandRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/init please")
            viewModel.sendPrompt()
            runCurrent()

            assertEquals("", viewModel.uiState.value.composer.text)
            assertTrue(commandRepository.runCommandCalled)
            commandGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("", viewModel.uiState.value.composer.text)
        }

    @Test
    fun successfulPrompt_clearsOnlySubmittedValuesAndPreservesNewEditsAndSelection() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdSendUntil = sendGate)
            val secondModel =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-4",
                    modelName = "GPT-4",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val defaultModel =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val viewModel =
                createViewModel(
                    repository,
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(listOf(defaultModel, secondModel), defaultModel),
                        ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val submitted = testAttachment()
            val newer = testAttachment(id = "attachment-2", filename = "new.txt", kind = AttachmentKind.Text)

            viewModel.updateComposerText("Submitted")
            viewModel.addAttachments(listOf(submitted))
            viewModel.sendPrompt()
            runCurrent()
            viewModel.updateComposerText("New edit")
            viewModel.addAttachments(listOf(newer))
            viewModel.selectModel(secondModel)
            sendGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("New edit", viewModel.uiState.value.composer.text)
            assertEquals(
                listOf(newer.id),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
            assertEquals(
                "gpt-4",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun successfulPrompt_preservesInFlightSameTextAndReaddedAttachment() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdSendUntil = sendGate)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val attachment = testAttachment()

            viewModel.updateComposerText("Submitted")
            viewModel.addAttachments(listOf(attachment))
            viewModel.sendPrompt()
            runCurrent()
            viewModel.updateComposerText("Edited")
            viewModel.updateComposerText("Submitted")
            viewModel.removeAttachment(attachment.id)
            viewModel.addAttachments(listOf(attachment))
            sendGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("Submitted", viewModel.uiState.value.composer.text)
            assertEquals(
                listOf(attachment.id),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
        }

    @Test
    fun repeatedAttachmentOnlySend_doesNotReconcileAgainstOlderMatchingMessage() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val oldFile =
                MessagePart.File(
                    id = "server-old",
                    filename = "notes.txt",
                    mime = "text/plain",
                    kind = AttachmentKind.Text,
                    dataUrl = "data:text/plain;base64,SGk=",
                )
            val repository =
                FakeMessageRepository(
                    messages =
                        listOf(
                            Message(id = "msg-old", role = MessageRole.User, text = "", parts = listOf(oldFile)),
                        ),
                    holdSendUntil = sendGate,
                )
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(listOf(testAttachment(id = "client-new", filename = "notes.txt", kind = AttachmentKind.Text)))
            viewModel.sendPrompt()
            runCurrent()
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceTimeBy(350)
            runCurrent()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.isPending },
            )
            sendGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun sendPrompt_shellDraft_runsCommandInsteadOfPrompt() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val rowsBefore =
                viewModel.uiState.value.messages
                    .map { it.id }

            viewModel.updateComposerText("!git status")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.composer.isShellMode)
            assertTrue(viewModel.uiState.value.composer.canSend)
            viewModel.sendPrompt()
            advanceUntilIdle()

            val (options, directory) = repository.shellRequests.single()
            assertEquals("git status", options.command)
            assertEquals(viewModel.uiState.value.composer.selectedMode, options.agent)
            assertEquals(
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
                options.model?.modelId,
            )
            assertEquals("/repo", directory)
            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertFalse(viewModel.uiState.value.isSending)
            // Output arrives through shell events; no optimistic prompt row is added.
            assertEquals(
                rowsBefore,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun sendPrompt_shellDraftWithPendingRevert_runsWithoutCommittingTheRevert() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val repository = FakeMessageRepository(messages = messages)
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.revertMessage("msg-1")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            viewModel.updateComposerText("!git status")
            viewModel.sendPrompt()
            advanceUntilIdle()

            // Like the TUI and web app, a shell command leaves a staged revert alone.
            assertEquals(1, repository.shellRequests.size)
            assertTrue(sessionRepository.commitRevertRequests.isEmpty())
            assertTrue(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun sendPrompt_bareBang_sendsNothing() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("!")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.composer.isShellMode)
            assertFalse(viewModel.uiState.value.composer.canSend)
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.shellRequests.isEmpty())
            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals("!", viewModel.uiState.value.composer.text)
        }

    @Test
    fun sendPrompt_leadingWhitespaceBeforeBang_sendsPrompt() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText(" !important")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.composer.isShellMode)
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.shellRequests.isEmpty())
            assertEquals("!important", repository.sentOptions?.text)
        }

    @Test
    fun sendPrompt_multiLineShellDraft_runsWholeScript() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("!cd app\nls\n")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(
                "cd app\nls",
                repository.shellRequests
                    .single()
                    .first.command,
            )
        }

    @Test
    fun sendPrompt_shellBusyFailure_restoresDraftAndShowsErrorLikeAPrompt() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            repository.shellError = MessageError.Http(statusCode = 409, message = "Session is busy")
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("!git status")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(1, repository.shellRequests.size)
            assertEquals("!git status", viewModel.uiState.value.composer.text)
            assertEquals(
                "Session is busy",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
        }

    @Test
    fun exitShellMode_dropsPrefixAndKeepsTheRestOfTheDraft() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("!git status")
            viewModel.exitShellMode()
            advanceUntilIdle()

            assertEquals("git status", viewModel.uiState.value.composer.text)
            assertFalse(viewModel.uiState.value.composer.isShellMode)
        }

    @Test
    fun sendPrompt_planMode_sendsPlanAgent() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.selectMode("plan")
            viewModel.updateComposerText("Plan me")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals("plan", repository.sentOptions?.mode)
        }

    @Test
    fun sendPrompt_customAgent_sendsAgentId() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel =
                createViewModel(
                    repository,
                    agentRepository =
                        FakeAgentRepository(
                            selectableAgents =
                                listOf(
                                    dev.opencode.mobile.domain.agent.SelectableAgent(
                                        id = "build",
                                        displayName = "Build",
                                    ),
                                    dev.opencode.mobile.domain.agent.SelectableAgent(
                                        id = "review",
                                        displayName = "Review",
                                    ),
                                ),
                        ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.selectMode("review")
            viewModel.updateComposerText("Review this")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals("review", repository.sentOptions?.mode)
            assertEquals("Review", viewModel.uiState.value.composer.selectedModeLabel)
        }

    @Test
    fun loadMessages_initializesModelFromLatestMessage() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-2",
                                    role = MessageRole.Assistant,
                                    text = "Reply",
                                    model = "claude-sonnet",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    variant = "high",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "anthropic",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
            assertEquals("high", viewModel.uiState.value.composer.selectedVariant)
        }

    @Test
    fun loadMessages_serverDefaultVariantIsDefaultWithoutNotice() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-2",
                                    role = MessageRole.Assistant,
                                    text = "Reply",
                                    model = "claude-sonnet",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    variant = "default",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
            assertNull(viewModel.uiState.value.composer.selectedVariant)
            assertNull(viewModel.uiState.value.composer.modelNotice)
        }

    @Test
    fun loadMessages_prefersLatestMessageModelOverCatalogDefault() =
        runTest(testDispatcher) {
            val defaultOption =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = listOf("low", "high"),
                    contextLimitTokens = 128_000,
                )
            val historyOption =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-4",
                    modelName = "GPT-4",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-2",
                                    role = MessageRole.Assistant,
                                    text = "Reply",
                                    modelProviderId = "openai",
                                    modelId = "gpt-4",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(
                                options = listOf(defaultOption, historyOption),
                                serverDefault = defaultOption,
                            ),
                        ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "openai",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "gpt-4",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun loadMessages_restoresCachedModelSelectionOverHistory() =
        runTest(testDispatcher) {
            val defaultOption =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = listOf("low", "high"),
                    contextLimitTokens = 128_000,
                )
            val cachedOption =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-4",
                    modelName = "GPT-4",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val selectionStore = FakeSessionModelSelectionStore()
            selectionStore.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection =
                    SessionModelSelection(
                        providerId = "openai",
                        modelId = "gpt-4",
                    ),
            )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-2",
                                    role = MessageRole.Assistant,
                                    text = "Reply",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    variant = "high",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(
                                options = listOf(defaultOption, cachedOption),
                                serverDefault = defaultOption,
                            ),
                        ),
                    sessionModelSelectionStore = selectionStore,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "openai",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "gpt-4",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun loadMessages_newerUserHistoryOverridesOlderCachedSelection() =
        runTest(testDispatcher) {
            val historyOption =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = listOf("high"),
                    contextLimitTokens = 128_000,
                )
            val cachedOption =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-4",
                    modelName = "GPT-4",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val selectionStore = FakeSessionModelSelectionStore()
            selectionStore.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection =
                    SessionModelSelection(
                        providerId = "openai",
                        modelId = "gpt-4",
                        savedAtEpochMs = 1_000L,
                    ),
            )
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(
                                        id = "msg-2",
                                        role = MessageRole.User,
                                        text = "Use Claude",
                                        modelProviderId = "anthropic",
                                        modelId = "claude-sonnet",
                                        variant = "high",
                                        sentAtEpochMs = 2_000L,
                                    ),
                                ),
                        ),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(
                                options = listOf(historyOption, cachedOption),
                                serverDefault = cachedOption,
                            ),
                        ),
                    sessionModelSelectionStore = selectionStore,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "anthropic",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
            assertEquals("high", viewModel.uiState.value.composer.selectedVariant)
        }

    @Test
    fun loadMessages_initializesAgentFromLatestUserMessage() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.User,
                                    text = "Plan this",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    mode = "plan",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals("plan", viewModel.uiState.value.composer.selectedMode)
            assertEquals("Plan", viewModel.uiState.value.composer.selectedModeLabel)
        }

    @Test
    fun loadMessages_newerUserAgentOverridesOlderCachedAgent() =
        runTest(testDispatcher) {
            val selectionStore = FakeSessionModelSelectionStore()
            selectionStore.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection =
                    SessionModelSelection(
                        providerId = "anthropic",
                        modelId = "claude-sonnet",
                        agentId = "build",
                        savedAtEpochMs = 1_000L,
                    ),
            )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.User,
                                    text = "Switch to plan",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    mode = "plan",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                    sessionModelSelectionStore = selectionStore,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals("plan", viewModel.uiState.value.composer.selectedMode)
        }

    @Test
    fun selectMode_persistsAgentForTheSessionWithoutNewSessionPreference() =
        runTest(testDispatcher) {
            val selectionStore = FakeSessionModelSelectionStore()
            val viewModel = createViewModel(FakeMessageRepository(), sessionModelSelectionStore = selectionStore)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.selectMode("plan")
            advanceUntilIdle()

            val identity = ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode")
            assertEquals("plan", selectionStore.loadSession(identity, "/repo", "ses-1")?.agentId)
            assertNull(selectionStore.loadNewSessionPreference(identity, "/repo")?.agentId)
        }

    @Test
    fun loadMessages_delayedAgentCatalog_restoresCustomAgentFromHistoryWithoutPersistingFallback() =
        runTest(testDispatcher) {
            val selectionStore = FakeSessionModelSelectionStore()
            val agentGate = CompletableDeferred<Unit>()
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.User,
                                    text = "Review this",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    mode = "review",
                                    sentAtEpochMs = 2_000L,
                                ),
                            ),
                    ),
                    agentRepository =
                        FakeAgentRepository(
                            selectableAgents =
                                listOf(
                                    dev.opencode.mobile.domain.agent.SelectableAgent(
                                        id = "build",
                                        displayName = "Build",
                                    ),
                                    dev.opencode.mobile.domain.agent.SelectableAgent(
                                        id = "review",
                                        displayName = "Review",
                                    ),
                                ),
                            selectableAgentsGate = agentGate,
                        ),
                    sessionModelSelectionStore = selectionStore,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            runCurrent()

            agentGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("review", viewModel.uiState.value.composer.selectedMode)
            val identity = ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode")
            assertEquals("review", selectionStore.loadSession(identity, "/repo", "ses-1")?.agentId)
        }

    @Test
    fun modelUpdatedEvent_refetchesCatalogThatWasEmptyAfterServerRestart() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(messages = emptyList())
            val models = FakeModelRepository(queued = listOf(ModelSelectionCatalog(emptyList(), null)))
            val viewModel = createViewModel(repository, modelRepository = models)

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "No usable models are available. Tap Model to retry.",
                viewModel.uiState.value.composer.modelCatalogError,
            )
            assertEquals(false, viewModel.uiState.value.composer.isModelSelectionReady)

            repository.eventStream.emit(OpencodeEvent(type = "provider.updated"))
            repository.eventStream.emit(OpencodeEvent(type = "model.updated"))
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)
            assertNull(viewModel.uiState.value.composer.modelCatalogError)
            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun streamReconnect_refetchesCatalogThatWasEmptyAfterServerRestart() =
        runTest(testDispatcher) {
            // The restart's catalog events fired while the stream was still down; only the
            // reconnect tells the composer the server is back.
            val repository = FakeMessageRepository(messages = emptyList())
            val models = FakeModelRepository(queued = listOf(ModelSelectionCatalog(emptyList(), null)))
            val viewModel = createViewModel(repository, modelRepository = models)

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.composer.isModelSelectionReady)

            repository.eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)
            assertNull(viewModel.uiState.value.composer.modelCatalogError)
            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun emptyCatalog_isRetriedWithBackoffUntilModelsArrive() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(messages = emptyList())
            val empty = ModelSelectionCatalog(emptyList(), null)
            val models = FakeModelRepository(queued = listOf(empty, empty))
            val viewModel = createViewModel(repository, modelRepository = models, catalogRetryDelaysMs = listOf(1_000L, 2_000L, 4_000L))

            viewModel.bind("ses-1", "Title", "/repo")
            runCurrent()
            assertEquals(false, viewModel.uiState.value.composer.isModelSelectionReady)
            assertEquals(1, models.calls)

            advanceTimeBy(1_001)
            assertEquals(2, models.calls)
            assertEquals(false, viewModel.uiState.value.composer.isModelSelectionReady)

            advanceTimeBy(2_001)
            advanceUntilIdle()
            assertEquals(3, models.calls)
            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)
            assertNull(viewModel.uiState.value.composer.modelCatalogError)
        }

    @Test
    fun emptyCatalog_stopsRetryingAfterTheLastBackoffStep() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(messages = emptyList())
            val empty = ModelSelectionCatalog(emptyList(), null)
            val models = FakeModelRepository(queued = listOf(empty, empty, empty, empty))
            val viewModel = createViewModel(repository, modelRepository = models, catalogRetryDelaysMs = listOf(10L, 10L))

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(3, models.calls)
            assertEquals(
                "No usable models are available. Tap Model to retry.",
                viewModel.uiState.value.composer.modelCatalogError,
            )
        }

    @Test
    fun reconnectRefreshThatComesBackEmpty_keepsTheLoadedCatalog() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(messages = emptyList())
            val models = FakeModelRepository()
            val viewModel = createViewModel(repository, modelRepository = models, catalogRetryDelaysMs = listOf(10L))

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)

            models.enqueue(ModelSelectionCatalog(emptyList(), null))
            repository.eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            advanceUntilIdle()

            assertEquals(3, models.calls)
            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)
            assertNull(viewModel.uiState.value.composer.modelCatalogError)
        }

    @Test
    fun unrelatedEvent_doesNotRefetchCatalog() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(messages = emptyList())
            val models = FakeModelRepository(queued = listOf(ModelSelectionCatalog(emptyList(), null)))
            val viewModel = createViewModel(repository, modelRepository = models)

            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val callsAfterBind = models.calls

            repository.eventStream.emit(OpencodeEvent(type = "skill.updated"))
            advanceUntilIdle()

            assertEquals(callsAfterBind, models.calls)
            assertEquals(false, viewModel.uiState.value.composer.isModelSelectionReady)
        }

    @Test
    fun initialization_waitsForLateCacheAndPublishesSelectionOnceReady() =
        runTest(testDispatcher) {
            val defaultOption =
                ModelSelectionOption("anthropic", "Anthropic", "claude-sonnet", "Claude Sonnet", emptyList(), 128_000)
            val cachedOption =
                ModelSelectionOption("openai", "OpenAI", "gpt-4", "GPT-4", emptyList(), 128_000)
            val gate = CompletableDeferred<Unit>()
            val store = FakeSessionModelSelectionStore(loadGate = gate)
            store.seed(
                "http://127.0.0.1:4096/",
                "/repo",
                "ses-1",
                SessionModelSelection("openai", "gpt-4", savedAtEpochMs = 2_000L),
            )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(messages = emptyList()),
                    modelRepository =
                        FakeModelRepository(ModelSelectionCatalog(listOf(defaultOption, cachedOption), defaultOption)),
                    sessionModelSelectionStore = store,
                )

            viewModel.bind("ses-1", "Title", "/repo")
            runCurrent()

            assertEquals(false, viewModel.uiState.value.composer.isModelSelectionReady)
            assertEquals(null, viewModel.uiState.value.composer.selectedModel)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)
            assertEquals(
                "gpt-4",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun selectModel_persistsAndRestoresOnRebind() =
        runTest(testDispatcher) {
            val defaultOption =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = listOf("low", "high"),
                    contextLimitTokens = 128_000,
                )
            val pickedOption =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-4",
                    modelName = "GPT-4",
                    variants = emptyList(),
                    contextLimitTokens = 128_000,
                )
            val selectionStore = FakeSessionModelSelectionStore()
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(
                                options = listOf(defaultOption, pickedOption),
                                serverDefault = defaultOption,
                            ),
                        ),
                    sessionModelSelectionStore = selectionStore,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.selectModel(pickedOption)
            advanceUntilIdle()

            viewModel.bind("ses-2", "Other", "/repo")
            advanceUntilIdle()
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "openai",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "gpt-4",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun selectModel_updatesNewSessionPreferenceForCurrentIdentityAndWorktree() =
        runTest(testDispatcher) {
            val pickedOption =
                ModelSelectionOption("openai", "OpenAI", "gpt-4", "GPT-4", emptyList(), 128_000)
            val store = FakeSessionModelSelectionStore()
            val viewModel =
                createViewModel(
                    FakeMessageRepository(),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(listOf(pickedOption), pickedOption),
                        ),
                    sessionModelSelectionStore = store,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.selectModel(pickedOption)
            advanceUntilIdle()

            assertEquals(
                "gpt-4",
                store
                    .preference(
                        ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                        "/repo",
                    )?.modelId,
            )
        }

    @Test
    fun loadMessages_showsNoticeWhenCachedModelUnavailable() =
        runTest(testDispatcher) {
            val defaultOption =
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-sonnet",
                    modelName = "Claude Sonnet",
                    variants = listOf("low", "high"),
                    contextLimitTokens = 128_000,
                )
            val selectionStore = FakeSessionModelSelectionStore()
            selectionStore.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection =
                    SessionModelSelection(
                        providerId = "removed",
                        modelId = "missing-model",
                    ),
            )
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.User,
                                    text = "Hello",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    sentAtEpochMs = 1_000L,
                                ),
                            ),
                    ),
                    modelRepository =
                        FakeModelRepository(
                            ModelSelectionCatalog(
                                options = listOf(defaultOption),
                                serverDefault = defaultOption,
                            ),
                        ),
                    sessionModelSelectionStore = selectionStore,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                "anthropic",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "Selected model is unavailable. Using Claude Sonnet.",
                viewModel.uiState.value.composer.modelNotice,
            )
        }

    @Test
    fun loadMessages_sortsOldestToNewest() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(id = "msg-2", role = MessageRole.User, text = "Later", sentAtEpochMs = 2_000L),
                                Message(id = "msg-1", role = MessageRole.User, text = "Earlier", sentAtEpochMs = 1_000L),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                listOf("Earlier", "Later"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )
        }

    @Test
    fun loadMessages_keepsStoreUserBeforeAssistantWhenUserTimestampIsLater() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(id = "msg-photo", role = MessageRole.User, text = "What is it", sentAtEpochMs = 3_000L),
                                Message(id = "msg-asst", role = MessageRole.Assistant, text = "A photo", sentAtEpochMs = 1_000L),
                                Message(id = "msg-hey", role = MessageRole.User, text = "hey", sentAtEpochMs = 2_000L),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                listOf("What is it", "A photo", "hey"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )
        }

    @Test
    fun loadMessages_preservesOrderWhenMessageTimestampIsMissing() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(id = "msg-1", role = MessageRole.Assistant, text = "Earlier", sentAtEpochMs = 1_000L),
                                Message(id = "msg-2", role = MessageRole.User, text = "Hydrating"),
                                Message(id = "msg-3", role = MessageRole.Assistant, text = "Later", sentAtEpochMs = 3_000L),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(
                listOf("Earlier", "Hydrating", "Later"),
                viewModel.uiState.value.messages
                    .map { it.text },
            )
        }

    @Test
    fun sendPrompt_failure_removesOptimisticMessage() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(shouldFailSend = true)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("Fail me")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals(false, viewModel.uiState.value.isSending)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.text == "Fail me" },
            )
            assertEquals(
                "send failed",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun sendPrompt_transportAfterBusy_keepsAcceptedPrompt() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository =
                FakeMessageRepository(
                    holdSendUntil = sendGate,
                    failSendWithTransport = true,
                )
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("Accepted prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()
            assertEquals("", viewModel.uiState.value.composer.text)
            assertTrue(viewModel.uiState.value.isSending)

            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.status",
                    sessionId = "ses-1",
                    rawProperties = buildJsonObject { put("type", "busy") },
                ),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sessionStatusLabel)

            sendGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertEquals(false, viewModel.uiState.value.isSending)
            assertNull(viewModel.uiState.value.snackbar)
            assertNull(viewModel.uiState.value.errorMessage)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == "Accepted prompt" && it.isPending },
            )
        }

    @Test
    fun sendPrompt_transportWhileIdle_restoresComposer() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(failSendWithTransport = true)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("Lost prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals(false, viewModel.uiState.value.isSending)
            assertEquals("Lost prompt", viewModel.uiState.value.composer.text)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.text == "Lost prompt" },
            )
            assertEquals(
                "Unable to reach the server",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun messageUpdatedEvent_reloadsMessages() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            repository.messages =
                listOf(
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Updated"),
                )
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceTimeBy(350)
            advanceUntilIdle()

            assertEquals(
                "Updated",
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
        }

    @Test
    fun sessionErrorEvent_surfacesMessageWithoutRestoringAttachments() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.error",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put("error", buildJsonObject { put("message", "Image exceeds the size limit") })
                        },
                ),
            )
            runCurrent()

            assertEquals(
                "Image exceeds the size limit",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
        }

    @Test
    fun sendPrompt_postSendReloadFailure_keepsOptimisticMessageWithoutError() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(failListMessagesAfterSend = true)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("New prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == "New prompt" && it.isPending },
            )
        }

    @Test
    fun messageUpdatedEvent_clearsStaleErrorBanner() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(failListMessagesAfterSend = true)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("New prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.errorMessage)

            repository.failListMessagesAfterSend = false
            repository.messages =
                listOf(
                    Message(id = requireNotNull(repository.sentOptions?.messageId), role = MessageRole.User, text = "New prompt"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceTimeBy(350)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
        }

    @Test
    fun quietRefreshDuringSend_keepsPendingMessageAndSendingFlag() =
        runTest(testDispatcher) {
            val sendGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdSendUntil = sendGate)
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("New prompt")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSending)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == "New prompt" && it.isPending },
            )

            repository.messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello"))
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceTimeBy(350)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSending)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == "New prompt" && it.isPending },
            )

            sendGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun connectionState_mapsToSessionConnectionStatus() =
        runTest(testDispatcher) {
            assertEquals(SessionConnectionStatus.Live, LiveUpdatesLabel.Live.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.Connecting, LiveUpdatesLabel.Connecting.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.Offline, LiveUpdatesLabel.Offline.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.AuthFailed, LiveUpdatesLabel.AuthFailed.toSessionConnectionStatus())
        }

    @Test
    fun contextUsageFraction_usesLatestAssistantTokensAndModelLimit() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(id = "msg-1", role = MessageRole.Assistant, text = "Earlier", contextInputTokens = 10_000),
                                Message(
                                    id = "msg-2",
                                    role = MessageRole.Assistant,
                                    text = "Latest",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    contextInputTokens = 32_000,
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(0.25f, viewModel.uiState.value.contextUsageFraction)
        }

    @Test
    fun contextUsageFraction_clampsAboveOne() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.Assistant,
                                    text = "Too much",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                    contextInputTokens = 160_000,
                                ),
                            ),
                    ),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(1f, viewModel.uiState.value.contextUsageFraction)
        }

    // MARK: - isStreaming propagation

    @Test
    fun toMessageItem_streamingTextPart_setsIsStreaming() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            val message =
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "Hello",
                    parts = listOf(MessagePart.Text(text = "Hello", isStreaming = true, id = "prt-1")),
                )
            val item = requireNotNull(viewModel.toMessageItem(message))
            assertTrue(item.isStreaming)
        }

    @Test
    fun toMessageItem_nonStreamingTextPart_setsIsStreamingFalse() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            val message =
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "Done",
                    parts = listOf(MessagePart.Text(text = "Done", isStreaming = false, id = "prt-1")),
                )
            val item = requireNotNull(viewModel.toMessageItem(message))
            assertFalse(item.isStreaming)
        }

    @Test
    fun toMessageItem_toolPartOnly_isNotStreaming() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            val message =
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts = listOf(MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-1")),
                )
            val item = requireNotNull(viewModel.toMessageItem(message))
            assertFalse(item.isStreaming)
            val tool = item.blocks.single() as SessionChatTimelineBlock.ToolCard
            assertEquals("Shell", tool.title)
        }

    @Test
    fun toMessageItem_userParts_preserveTextAndAttachmentOrder() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            val firstFile =
                MessagePart.File(
                    id = "file-1",
                    filename = "first.txt",
                    mime = "text/plain",
                    kind = AttachmentKind.Text,
                    dataUrl = "data:text/plain;base64,QQ==",
                )
            val secondFile = firstFile.copy(id = "file-2", filename = "second.txt", dataUrl = "data:text/plain;base64,Qg==")
            val message =
                Message(
                    id = "msg-1",
                    role = MessageRole.User,
                    text = "Between",
                    parts = listOf(firstFile, MessagePart.Text("Between", id = "prt-text"), secondFile),
                )

            val item = requireNotNull(viewModel.toMessageItem(message))

            assertEquals(3, item.blocks.size)
            assertEquals("first.txt", (item.blocks[0] as SessionChatTimelineBlock.Attachment).attachment.filename)
            assertEquals("Between", (item.blocks[1] as SessionChatTimelineBlock.Text).text)
            assertEquals("second.txt", (item.blocks[2] as SessionChatTimelineBlock.Attachment).attachment.filename)
        }

    @Test
    fun toMessageItem_attachmentOnlyUserMessage_isKept() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            val file =
                MessagePart.File(
                    id = "file-1",
                    filename = "notes.txt",
                    mime = "text/plain",
                    kind = AttachmentKind.Text,
                    dataUrl = "data:text/plain;base64,SGk=",
                )

            val item =
                requireNotNull(
                    viewModel.toMessageItem(
                        Message(id = "msg-1", role = MessageRole.User, text = "", parts = listOf(file)),
                    ),
                )

            assertEquals(1, item.blocks.size)
            assertTrue(item.blocks.single() is SessionChatTimelineBlock.Attachment)
        }

    @Test
    fun contextUsageFraction_unavailableWhenTokensOrLimitMissing() =
        runTest(testDispatcher) {
            val noTokens =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.Assistant,
                                    text = "No tokens",
                                    modelProviderId = "anthropic",
                                    modelId = "claude-sonnet",
                                ),
                            ),
                    ),
                )
            noTokens.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(null, noTokens.uiState.value.contextUsageFraction)

            val noLimit =
                createViewModel(
                    FakeMessageRepository(
                        messages =
                            listOf(
                                Message(
                                    id = "msg-1",
                                    role = MessageRole.Assistant,
                                    text = "No limit",
                                    modelProviderId = "missing",
                                    modelId = "model",
                                    contextInputTokens = 12_000,
                                ),
                            ),
                    ),
                )
            noLimit.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(null, noLimit.uiState.value.contextUsageFraction)
        }

    @Test
    fun toMessageItem_preservesRenderedMarkdownWhileStreaming() {
        val viewModel = createViewModel(FakeMessageRepository())
        val previous =
            SessionChatMessageItem(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "# Partial",
                metadataLabel = "Build",
                isStreaming = true,
                renderedMarkdown =
                    dev.opencode.mobile.markdown.MarkdownDocument(
                        sourceHash = "# Partial".hashCode(),
                        blocks =
                            listOf(
                                dev.opencode.mobile.markdown.MarkdownBlock.Heading(
                                    level = 1,
                                    inlines =
                                        listOf(
                                            dev.opencode.mobile.markdown.MarkdownInline
                                                .Text("Partial"),
                                        ),
                                ),
                            ),
                    ),
            )

        val item =
            requireNotNull(
                viewModel.toMessageItem(
                    Message(
                        id = "msg-1",
                        role = MessageRole.Assistant,
                        text = "# Partial heading",
                        parts = listOf(MessagePart.Text(text = "# Partial heading", isStreaming = true, id = "prt-1")),
                    ),
                    previous,
                ),
            )

        assertNotNull(item.renderedMarkdown)
    }

    @Test
    fun openCommands_showsSheetAndDismissesSuggestionsKeepingText() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("/in")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.slashSuggestionsVisible)

            viewModel.openCommands()
            runCurrent()

            assertTrue(viewModel.uiState.value.commandsSheetVisible)
            assertFalse(viewModel.uiState.value.slashSuggestionsVisible)
            assertFalse(viewModel.uiState.value.atSuggestionsVisible)
            assertEquals("/in", viewModel.uiState.value.composer.text)
        }

    @Test
    fun clientSlashActions_visibility_reflectsCatalogGates() =
        runTest(testDispatcher) {
            val emptyMessages = createViewModel(FakeMessageRepository(messages = emptyList()))
            emptyMessages.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                listOf(ClientSlashAction.New, ClientSlashAction.Terminal, ClientSlashAction.Files, ClientSlashAction.Mcp),
                emptyMessages.uiState.value.visibleClientSlashActions,
            )

            val withMessages = createViewModel(FakeMessageRepository())
            withMessages.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val visible = withMessages.uiState.value.visibleClientSlashActions
            assertTrue(ClientSlashAction.New in visible)
            assertTrue(ClientSlashAction.Terminal in visible)
            assertTrue(ClientSlashAction.Fork in visible)
            assertTrue(ClientSlashAction.Undo in visible)
            assertTrue(ClientSlashAction.Compact in visible)
            assertFalse(ClientSlashAction.Redo in visible)
        }

    @Test
    fun runClientSlashAction_files_requestsFileBrowserForWorktree() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.openCommands()

            viewModel.runClientSlashAction(ClientSlashAction.Files)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.commandsSheetVisible)
            assertEquals("/repo", viewModel.uiState.value.pendingFilesOpen)

            viewModel.consumePendingFilesOpen()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.pendingFilesOpen)
        }

    private fun moveProjectsSnapshot(
        worktrees: List<String> = listOf("/repo", "/repo-feature"),
        isRepository: Boolean = true,
    ) = dev.opencode.mobile.domain.home.HomeProjectsSnapshot().apply {
        publish(
            listOf(
                dev.opencode.mobile.domain.home.HomeProject(
                    key = "proj",
                    projectId = "proj",
                    displayName = "repo",
                    memberPaths = listOf("/repo"),
                    isRepository = isRepository,
                    worktrees =
                        worktrees.mapIndexed { index, directory ->
                            dev.opencode.mobile.domain.home.HomeWorktree(
                                directory = directory,
                                branch = if (index == 0) "dev" else "feature/x",
                                isMain = index == 0,
                            )
                        },
                ),
            ),
        )
    }

    @Test
    fun moveToWorktree_menuEnablementFollowsProtocolSettingAndWorktrees() =
        runTest(testDispatcher) {
            val v1 = createViewModel(repository = FakeMessageRepository(), homeProjectsSnapshot = moveProjectsSnapshot())
            v1.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals("Needs an opencode v2 server", v1.uiState.value.moveToWorktree.availability.subtitle)
            assertFalse(v1.uiState.value.moveToWorktree.availability.enabled)

            // One checkout: the sheet still opens while "New worktree…" can make another.
            val single =
                createViewModel(
                    repository = FakeMessageRepository(),
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(listOf("/repo")),
                )
            single.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(single.uiState.value.moveToWorktree.availability.enabled)
            assertTrue(single.uiState.value.moveToWorktree.newWorktree.enabled)

            val singlePlain =
                createViewModel(
                    repository = FakeMessageRepository(),
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(listOf("/repo"), isRepository = false),
                )
            singlePlain.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals("This project has no other worktrees", singlePlain.uiState.value.moveToWorktree.availability.subtitle)
            assertEquals("Needs a git repository", singlePlain.uiState.value.moveToWorktree.newWorktree.subtitle)

            val v2 =
                createViewModel(
                    repository = FakeMessageRepository(),
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(),
                )
            v2.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(v2.uiState.value.moveToWorktree.availability.enabled)
            assertEquals(
                listOf(true, false),
                v2.uiState.value.moveToWorktree.rows
                    .map { it.isCurrent },
            )

            HomeDisplayPreferences.updateShowsWorktrees(false)
            try {
                androidx.compose.runtime.snapshots.Snapshot
                    .sendApplyNotifications()
                advanceUntilIdle()
                assertEquals("Turn on Worktrees in Settings", v2.uiState.value.moveToWorktree.availability.subtitle)
            } finally {
                HomeDisplayPreferences.updateShowsWorktrees(true)
            }
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            advanceUntilIdle()
            assertTrue(v2.uiState.value.moveToWorktree.availability.enabled)
        }

    @Test
    fun moveToNewWorktree_createsFromTheSessionCheckoutThenMovesThere() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val requests = mutableListOf<CreateWorktreeRequest>()
            val worktrees =
                object : WorktreeRepository by UnavailableWorktreeRepository {
                    override suspend fun baseBranches(directory: String): List<String> = listOf("dev", "main")

                    override suspend fun create(request: CreateWorktreeRequest): CreatedWorktree {
                        requests += request
                        return CreatedWorktree("/wt/ab12cd/brave-otter", "brave-otter", branch = null)
                    }
                }
            val snapshot = moveProjectsSnapshot(listOf("/repo"))
            val refreshes = mutableListOf<String>()
            backgroundScope.launch { snapshot.worktreeRefreshRequests.collect { refreshes += it } }
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = snapshot,
                    worktreeRepository = worktrees,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(
                NewWorktreeAvailability(true, "Create a checkout and move there"),
                viewModel.uiState.value.moveToWorktree.newWorktree,
            )

            viewModel.openMoveToWorktree()
            viewModel.openMoveToNewWorktree()
            advanceUntilIdle()
            val form = viewModel.uiState.value.moveToWorktree.form!!
            assertEquals("/repo", form.sourceDirectory)
            assertEquals("dev", form.sourceBranch)
            assertEquals("Create and move", form.submitLabel)
            viewModel.updateNewWorktreeName("brave-otter")
            viewModel.selectNewWorktreeBase("main")
            viewModel.submitNewWorktree()
            advanceUntilIdle()

            assertEquals(listOf(CreateWorktreeRequest("proj", "/repo", "brave-otter", baseRef = "main")), requests)
            assertEquals(listOf("ses-1" to "/wt/ab12cd/brave-otter"), sessionRepository.moveSessionRequests)
            assertNull(viewModel.uiState.value.moveToWorktree.form)
            assertFalse(viewModel.uiState.value.moveToWorktree.sheetVisible)
            assertEquals(listOf("/repo"), refreshes)
        }

    @Test
    fun moveToNewWorktree_failedCreateDoesNotMove() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val worktrees =
                object : WorktreeRepository by UnavailableWorktreeRepository {
                    override suspend fun create(request: CreateWorktreeRequest): CreatedWorktree =
                        throw WorktreeCreateError("fatal: invalid reference: nope")
                }
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(),
                    worktreeRepository = worktrees,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.openMoveToWorktree()
            viewModel.openMoveToNewWorktree()
            viewModel.submitNewWorktree()
            advanceUntilIdle()

            assertTrue(sessionRepository.moveSessionRequests.isEmpty())
            val move = viewModel.uiState.value.moveToWorktree
            assertTrue(move.sheetVisible)
            assertEquals("Couldn't create the worktree: fatal: invalid reference: nope", move.form?.errorMessage)

            viewModel.closeNewWorktreeForm()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.moveToWorktree.form)
            assertTrue(viewModel.uiState.value.moveToWorktree.sheetVisible)
        }

    @Test
    fun sideQuestion_typedBtwAsksOnceWithoutTouchingTheConversation() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val actions = viewModel.uiState.value.visibleClientSlashActions
            assertEquals(ClientSlashAction.Btw, actions.first())
            val messagesBefore = viewModel.uiState.value.messages

            viewModel.updateComposerText("/btw ")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.sideQuestions.composerMode)
            assertEquals("", viewModel.uiState.value.composer.text)

            viewModel.updateComposerText("What changed?")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(listOf("ses-1" to "What changed?"), sessionRepository.sideQuestionRequests)
            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertFalse(viewModel.uiState.value.sideQuestions.composerMode)
            assertEquals(1, viewModel.uiState.value.sideQuestions.pendingCount)

            sessionRepository.sideQuestionReplies.single().complete("Two files.")
            advanceUntilIdle()

            val side = viewModel.uiState.value.sideQuestions
            assertEquals("What changed?", side.presentedAnswer?.question)
            assertEquals("Two files.", side.presentedAnswer?.answer)
            assertEquals(0, side.pendingCount)
            assertEquals(messagesBefore, viewModel.uiState.value.messages)
            assertFalse(repository.sendPromptAsyncCalled)
        }

    @Test
    fun sideQuestion_btwTileEntersModeAndEmptyQuestionIsNotSent() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Btw)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.sideQuestions.composerMode)
            assertFalse(viewModel.uiState.value.commandsSheetVisible)

            viewModel.sendPrompt()
            advanceUntilIdle()
            assertTrue(sessionRepository.sideQuestionRequests.isEmpty())

            viewModel.exitSideQuestionMode()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.sideQuestions.composerMode)
        }

    @Test
    fun sideQuestion_unsupportedServerShowsNoticeAndNeverSendsAPrompt() =
        runTest(testDispatcher) {
            listOf(ServerProtocol.V1, ServerProtocol.V2).forEach { protocol ->
                val repository = FakeMessageRepository()
                val sessionRepository = FakeSessionRepository()
                val viewModel =
                    createViewModel(repository = repository, sessionRepository = sessionRepository, serverProtocol = protocol)
                viewModel.bind("ses-1", "Title", "/repo")
                advanceUntilIdle()
                assertFalse(ClientSlashAction.Btw in viewModel.uiState.value.visibleClientSlashActions)

                viewModel.updateComposerText("/btw why?")
                advanceUntilIdle()
                assertEquals(SessionChatSideQuestionsCopy.UNSUPPORTED, viewModel.uiState.value.sideQuestions.unsupportedNotice)
                assertFalse(viewModel.uiState.value.sideQuestions.composerMode)

                viewModel.sendPrompt()
                advanceUntilIdle()
                assertTrue(sessionRepository.sideQuestionRequests.isEmpty())
                assertFalse(repository.sendPromptAsyncCalled)
                assertEquals("/btw why?", viewModel.uiState.value.composer.text)
            }
        }

    @Test
    fun sideQuestion_answerArrivingUnderAnOpenSheetWaitsThenOpens() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("/btw One?")
            viewModel.sendPrompt()
            viewModel.updateComposerText("/btw Two?")
            viewModel.sendPrompt()
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.sideQuestions.pendingCount)

            sessionRepository.sideQuestionReplies[0].complete("First")
            advanceUntilIdle()
            sessionRepository.sideQuestionReplies[1].complete("Second")
            advanceUntilIdle()

            var side = viewModel.uiState.value.sideQuestions
            assertEquals("First", side.presentedAnswer?.answer)
            assertEquals(1, side.readyCount)

            viewModel.closeSideQuestionAnswer()
            advanceUntilIdle()
            side = viewModel.uiState.value.sideQuestions
            assertEquals("Second", side.presentedAnswer?.answer)
            assertEquals(0, side.readyCount)

            viewModel.closeSideQuestionAnswer()
            advanceUntilIdle()
            side = viewModel.uiState.value.sideQuestions
            assertNull(side.presentedAnswer)
            assertEquals(0, side.readyCount)
        }

    @Test
    fun sideQuestion_failureOffersRetryOnTheSnackbarAndChip() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("/btw Why?")
            viewModel.sendPrompt()
            advanceUntilIdle()

            sessionRepository.sideQuestionReplies.single().completeExceptionally(SessionError.Http(503, "busy"))
            advanceUntilIdle()

            val snackbar = viewModel.uiState.value.snackbar
            assertEquals("/btw failed: “Why?”", snackbar?.message)
            assertEquals(SessionChatSnackbarTone.Error, snackbar?.tone)
            val retry = snackbar?.action as SessionChatSnackbarAction.RetrySideQuestion
            assertEquals(1, viewModel.uiState.value.sideQuestions.failedCount)

            viewModel.retrySideQuestions(retry.id)
            advanceUntilIdle()
            assertEquals(2, sessionRepository.sideQuestionRequests.size)
            assertEquals(0, viewModel.uiState.value.sideQuestions.failedCount)
            assertEquals(1, viewModel.uiState.value.sideQuestions.pendingCount)

            sessionRepository.sideQuestionReplies.last().completeExceptionally(SessionError.Http(503, "busy"))
            advanceUntilIdle()
            viewModel.dismissFailedSideQuestions()
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.sideQuestions.failedCount)
        }

    @Test
    fun sideQuestion_requestsAndUnreadAnswersOutliveLeavingSessionChat() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val store =
                SideQuestionStore(
                    request = { sessionId, question -> sessionRepository.askSideQuestion(sessionId, question) },
                    scope = CoroutineScope(SupervisorJob() + testDispatcher),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                    sideQuestionStore = store,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("/btw Why?")
            viewModel.sendPrompt()
            advanceUntilIdle()

            viewModel.leaveSideQuestions()
            advanceUntilIdle()
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.sideQuestions.pendingCount)

            viewModel.leaveSideQuestions()
            sessionRepository.sideQuestionReplies.single().complete("Because.")
            advanceUntilIdle()
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            var side = viewModel.uiState.value.sideQuestions
            assertEquals(1, side.readyCount)
            assertNull(side.presentedAnswer)

            viewModel.openSideQuestionAnswer()
            advanceUntilIdle()
            side = viewModel.uiState.value.sideQuestions
            assertEquals("Because.", side.presentedAnswer?.answer)

            viewModel.leaveSideQuestions()
            advanceUntilIdle()
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            side = viewModel.uiState.value.sideQuestions
            assertEquals(0, side.readyCount)
            assertNull(side.presentedAnswer)
            assertTrue(store.entries("ses-1").isEmpty())
        }

    @Test
    fun sideQuestion_btwTileOnABtwDraftAsksOnlyTheQuestion() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.restoreComposerText("/btw Why?")
            viewModel.runClientSlashAction(ClientSlashAction.Btw)
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(listOf("ses-1" to "Why?"), sessionRepository.sideQuestionRequests)
        }

    @Test
    fun sideQuestion_leavingInModeKeepsTheDraftASideQuestion() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("/btw ")
            viewModel.updateComposerText("Why?")
            advanceUntilIdle()

            viewModel.leaveSideQuestions()
            advanceUntilIdle()
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.sideQuestions.composerMode)
            assertEquals("/btw Why?", viewModel.uiState.value.composer.text)

            viewModel.sendPrompt()
            advanceUntilIdle()
            assertEquals(listOf("ses-1" to "Why?"), sessionRepository.sideQuestionRequests)
            assertFalse(repository.sendPromptAsyncCalled)
        }

    @Test
    fun sideQuestion_answerWaitsBehindAScreenSheet() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    serverProtocol = ServerProtocol.V2,
                    sideQuestionsSupported = true,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("/btw Why?")
            viewModel.sendPrompt()
            advanceUntilIdle()

            viewModel.setScreenSheetOpen(true)
            sessionRepository.sideQuestionReplies.single().complete("Because.")
            advanceUntilIdle()

            val side = viewModel.uiState.value.sideQuestions
            assertNull(side.presentedAnswer)
            assertEquals(1, side.readyCount)
        }

    @Test
    fun moveToWorktree_v2PostsTheMoveAndSessionMovedRebindsInPlace() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository = FakeSessionRepository().apply { vcsBranches = mapOf("/repo" to "dev", "/repo-feature" to "feature/x") }
            val commandRepository = FakeCommandRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                    commandRepository = commandRepository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("draft in progress")
            val frameBefore = viewModel.uiState.value.timelineFrame
            assertEquals("dev", viewModel.uiState.value.sessionBranchPill?.branch)

            viewModel.openMoveToWorktree()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.moveToWorktree.sheetVisible)
            viewModel.moveToWorktree("/repo-feature")
            advanceUntilIdle()

            assertEquals(listOf("ses-1" to "/repo-feature"), sessionRepository.moveSessionRequests)
            assertFalse(viewModel.uiState.value.moveToWorktree.sheetVisible)
            assertEquals("/repo", viewModel.uiState.value.worktree)

            repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo-feature"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.pendingSessionReplace)
            assertEquals("/repo-feature", state.worktree)
            assertEquals("repo-feature", state.projectLabel)
            assertEquals("feature/x", state.sessionBranchPill?.branch)
            assertEquals("/repo-feature", sessionRepository.vcsInfoRequests.last())
            assertEquals(listOf(false, true), state.moveToWorktree.rows.map { it.isCurrent })
            assertEquals("/repo-feature", commandRepository.listCommandsDirectories.last())
            // The timeline keeps its transcript: no reload, same frame, draft intact.
            assertEquals(SessionChatTimelineState.Ready, state.timeline)
            assertEquals(listOf<String?>("/repo"), repository.observedDisplayDirectories)
            assertEquals(frameBefore?.rows?.map { it.id }, state.timelineFrame?.rows?.map { it.id })
            assertEquals("draft in progress", state.composer.text)
            assertEquals("claude-sonnet", state.composer.selectedModel?.option?.modelId)
            assertNull(state.composer.modelNotice)
        }

    @Test
    fun sessionMoved_modelTheNewDirectoryLacksFallsBackWithANotice() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val gpt =
                ModelSelectionOption(
                    providerId = "openai",
                    providerName = "OpenAI",
                    modelId = "gpt-5",
                    modelName = "GPT-5",
                )
            val modelRepository =
                FakeModelRepository().apply {
                    catalogsByDirectory = mapOf("/repo-feature" to ModelSelectionCatalog(options = listOf(gpt), serverDefault = gpt))
                }
            val viewModel =
                createViewModel(
                    repository = repository,
                    modelRepository = modelRepository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals("claude-sonnet", viewModel.uiState.value.composer.selectedModel?.option?.modelId)

            repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo-feature"))
            advanceUntilIdle()

            val composer = viewModel.uiState.value.composer
            assertEquals("gpt-5", composer.selectedModel?.option?.modelId)
            assertEquals("Selected model is unavailable. Using ${gpt.modelLabel}.", composer.modelNotice)
        }

    @Test
    fun sessionMoved_routeRebindWithTheOldDirectoryKeepsTheNewOne() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo-feature"))
            advanceUntilIdle()

            // Back from Files or a configuration change: the route still names /repo.
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals("/repo-feature", viewModel.uiState.value.worktree)
            assertEquals(SessionChatTimelineState.Ready, viewModel.uiState.value.timeline)
            assertNull(viewModel.uiState.value.pendingSessionReplace)
        }

    @Test
    fun sessionMoved_rapidMovesEndInTheLastDirectory() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(listOf("/repo", "/repo-feature", "/repo-fix")),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            repeat(3) {
                repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo-feature"))
                repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo-fix"))
                repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo"))
            }
            repository.eventStream.emit(sessionMovedEvent("ses-1", "/repo-fix"))
            advanceUntilIdle()

            assertEquals("/repo-fix", viewModel.uiState.value.worktree)
            assertEquals("repo-fix", viewModel.uiState.value.projectLabel)
            assertEquals(listOf(false, false, true), viewModel.uiState.value.moveToWorktree.rows.map { it.isCurrent })
            assertEquals(listOf<String?>("/repo"), repository.observedDisplayDirectories)
        }

    @Test
    fun sessionMoved_forAnotherSessionChangesNothing() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    serverProtocol = ServerProtocol.V2,
                    homeProjectsSnapshot = moveProjectsSnapshot(),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            repository.eventStream.emit(sessionMovedEvent("ses-2", "/repo-feature"))
            advanceUntilIdle()

            assertEquals("/repo", viewModel.uiState.value.worktree)
            assertEquals("repo", viewModel.uiState.value.projectLabel)
        }

    private fun sessionMovedEvent(
        sessionId: String,
        directory: String,
    ) = OpencodeEvent(
        type = EventDomainAdapter.SESSION_MOVED_EVENT,
        sessionId = sessionId,
        rawProperties =
            kotlinx.serialization.json.buildJsonObject {
                put("sessionID", kotlinx.serialization.json.JsonPrimitive(sessionId))
                put(
                    "location",
                    kotlinx.serialization.json.buildJsonObject {
                        put("directory", kotlinx.serialization.json.JsonPrimitive(directory))
                    },
                )
            },
    )

    @Test
    fun runClientSlashAction_new_requestsOptimisticReplaceKeepsDraftNeverRunsCommand() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    createSessionResult = Session(id = "ses-2", title = "New session", directory = "/repo"),
                )
            val commandRepository = FakeCommandRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    commandRepository = commandRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.updateComposerText("draft that should survive")
            viewModel.openCommands()

            viewModel.runClientSlashAction(ClientSlashAction.New)
            advanceUntilIdle()

            assertNull(sessionRepository.createSessionRequest)
            assertFalse(viewModel.uiState.value.commandsSheetVisible)
            assertEquals(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                viewModel.uiState.value.pendingSessionReplace
                    ?.sessionId,
            )
            assertEquals(
                "New Session",
                viewModel.uiState.value.pendingSessionReplace
                    ?.sessionTitle,
            )
            assertEquals(
                "/repo",
                viewModel.uiState.value.pendingSessionReplace
                    ?.worktree,
            )
            assertNotNull(
                viewModel.uiState.value.pendingSessionReplace
                    ?.optimisticNavigationInstanceId,
            )
            assertEquals("draft that should survive", viewModel.uiState.value.composer.text)
            assertFalse(commandRepository.runCommandCalled)

            viewModel.consumePendingSessionReplace()
            runCurrent()
            assertEquals(null, viewModel.uiState.value.pendingSessionReplace)
        }

    @Test
    fun bindOptimisticCreate_promotesSessionAndLoadsMessages() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    createSessionResult = Session(id = "ses-new", title = "Created", directory = "/repo"),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()

            assertEquals("ses-new", viewModel.uiState.value.sessionId)
            assertTrue(
                viewModel.uiState.value.timeline == SessionChatTimelineState.Empty ||
                    viewModel.uiState.value.timeline == SessionChatTimelineState.Ready,
            )
            assertEquals(
                FakeSessionRepository.LifecycleRequest(id = "", directory = "/repo"),
                sessionRepository.createSessionRequest,
            )
        }

    @Test
    fun bindOptimisticCreate_failureStaysOnChatWithError() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository =
                FakeSessionRepository(createSessionError = SessionError.Http(500, "boom"))
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                )
            viewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.shouldLeaveSession)
            assertEquals(SessionChatTimelineState.Error, viewModel.uiState.value.timeline)
            assertEquals("boom", viewModel.uiState.value.errorMessage)
            assertEquals(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, viewModel.uiState.value.sessionId)

            viewModel.updateComposerText("hi")
            viewModel.sendPrompt()
            viewModel.archiveSession()
            viewModel.deleteSession()
            advanceUntilIdle()
            assertEquals(false, repository.sendPromptAsyncCalled)
            assertEquals(0, sessionRepository.archiveCallCount)
            assertEquals(0, sessionRepository.deleteCallCount)
        }

    @Test
    fun bindOptimisticCreate_transportFailureShowsReachabilityError() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    createSessionError = SessionError.Transport(RuntimeException()),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.shouldLeaveSession)
            assertEquals(SessionChatTimelineState.Error, viewModel.uiState.value.timeline)
            assertEquals("Unable to reach the server", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun bindPlaceholderAfterPromotion_doesNotRecreateSession() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    createSessionResult = Session(id = "ses-new", title = "Created", directory = "/repo"),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()
            assertEquals("ses-new", viewModel.uiState.value.sessionId)
            assertEquals(1, sessionRepository.createSessionCallCount)

            viewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()

            assertEquals("ses-new", viewModel.uiState.value.sessionId)
            assertEquals(1, sessionRepository.createSessionCallCount)
            assertTrue(viewModel.uiState.value.timeline != SessionChatTimelineState.Creating)
        }

    @Test
    fun bindOptimisticCreate_freshViewModelAfterPromotion_createsSecondSession() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    createSessionResult = Session(id = "ses-new", title = "Created", directory = "/repo"),
                )
            val firstViewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            firstViewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()
            assertEquals("ses-new", firstViewModel.uiState.value.sessionId)
            assertEquals(1, sessionRepository.createSessionCallCount)

            val secondViewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            secondViewModel.bind(
                OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                "New Session",
                "/repo",
            )
            advanceUntilIdle()

            assertEquals("ses-new", secondViewModel.uiState.value.sessionId)
            assertEquals(2, sessionRepository.createSessionCallCount)
        }

    @Test
    fun runClientSlashAction_fork_confirmAndOpen_navigatesToForkedSession() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    forkSessionResult = Session(id = "ses-3", title = "Original", directory = "/repo"),
                )
            val commandRepository = FakeCommandRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    commandRepository = commandRepository,
                )
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Fork)
            runCurrent()
            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertEquals("Fork of Original", viewModel.uiState.value.forkDraftTitle)
            assertFalse(viewModel.uiState.value.commandsSheetVisible)

            viewModel.updateForkDraft("Forked copy")
            viewModel.confirmFork()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.ForkRequest(id = "ses-1", atMessageId = null, directory = "/repo"),
                sessionRepository.forkSessionRequest,
            )
            assertEquals(
                FakeSessionRepository.UpdateRequest(id = "ses-3", title = "Forked copy", directory = "/repo"),
                sessionRepository.updateRequest,
            )
            assertFalse(viewModel.uiState.value.forkDialogVisible)
            assertTrue(viewModel.uiState.value.forkResultVisible)
            assertEquals("Forked copy", viewModel.uiState.value.forkedSessionTitle)
            assertFalse(commandRepository.runCommandCalled)

            viewModel.chooseForkOpen()
            runCurrent()
            assertFalse(viewModel.uiState.value.forkResultVisible)
            assertEquals(
                ChildSessionNavigationTarget(sessionId = "ses-3", sessionTitle = "Forked copy", worktree = "/repo"),
                viewModel.uiState.value.pendingSessionReplace,
            )
        }

    @Test
    fun runClientSlashAction_fork_stay_dismissesWithoutNavigating() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    forkSessionResult = Session(id = "ses-3", title = "Original", directory = "/repo"),
                )
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Fork)
            viewModel.updateForkDraft("Forked copy")
            viewModel.confirmFork()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.forkResultVisible)

            viewModel.chooseForkStay()
            runCurrent()
            assertFalse(viewModel.uiState.value.forkResultVisible)
            assertEquals(null, viewModel.uiState.value.pendingSessionReplace)
        }

    @Test
    fun runClientSlashAction_fork_failure_keepsDialogOpenWithError() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(forkSessionError = SessionError.Http(500, "boom"))
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Fork)
            viewModel.updateForkDraft("Forked copy")
            viewModel.confirmFork()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertFalse(viewModel.uiState.value.forkSubmitting)
            assertNotNull(viewModel.uiState.value.forkErrorMessage)
        }

    @Test
    fun openForkSession_overflow_forksAtTipWithPrefilledTitle() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val sessionRepository =
                FakeSessionRepository(
                    forkSessionResult = Session(id = "ses-3", title = "Original", directory = "/repo"),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    composerDraftStore = draftStore,
                )
            viewModel.bind("ses-1", "  Original  ", "/repo")
            advanceUntilIdle()

            viewModel.openForkSession()
            runCurrent()
            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertEquals("Fork of Original", viewModel.uiState.value.forkDraftTitle)

            viewModel.confirmFork()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.ForkRequest(id = "ses-1", atMessageId = null, directory = "/repo"),
                sessionRepository.forkSessionRequest,
            )
            assertEquals(
                FakeSessionRepository.UpdateRequest(id = "ses-3", title = "Fork of Original", directory = "/repo"),
                sessionRepository.updateRequest,
            )
            assertTrue(viewModel.uiState.value.forkResultVisible)
            assertEquals("Fork of Original", viewModel.uiState.value.forkedSessionTitle)
            assertNull(viewModel.uiState.value.snackbar)
            assertNull(
                draftStore.load(
                    ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                    "/repo",
                    "ses-3",
                ),
            )
        }

    @Test
    fun openForkSession_blankTitle_prefillsForkedSession() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(repository = FakeMessageRepository())
            viewModel.bind("ses-1", "   ", "/repo")
            advanceUntilIdle()

            viewModel.openForkSession()
            runCurrent()

            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertEquals("Forked session", viewModel.uiState.value.forkDraftTitle)
        }

    @Test
    fun forkFromMessage_passesMessageIdAndPrefillsForkComposer() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val sessionRepository =
                FakeSessionRepository(
                    forkSessionResult = Session(id = "ses-3", title = "Original", directory = "/repo"),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    composerDraftStore = draftStore,
                )
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.forkFromMessage(messageId = "msg-2", messageText = "Try a different approach")
            runCurrent()
            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertEquals("Fork of Original", viewModel.uiState.value.forkDraftTitle)

            viewModel.confirmFork()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.ForkRequest(id = "ses-1", atMessageId = "msg-2", directory = "/repo"),
                sessionRepository.forkSessionRequest,
            )
            assertEquals(
                "Try a different approach",
                draftStore
                    .load(
                        ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                        "/repo",
                        "ses-3",
                    )?.text,
            )
            assertTrue(viewModel.uiState.value.forkResultVisible)

            viewModel.chooseForkOpen()
            runCurrent()
            assertEquals(
                "ses-3",
                viewModel.uiState.value.pendingSessionReplace
                    ?.sessionId,
            )

            val fork = createViewModel(FakeMessageRepository(), composerDraftStore = draftStore)
            fork.bind("ses-3", "Fork of Original", "/repo")
            advanceUntilIdle()
            assertEquals("Try a different approach", fork.uiState.value.composer.text)
        }

    @Test
    fun confirmFork_renameFailure_offersForkOnceAndShowsSnackbar() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val sessionRepository =
                FakeSessionRepository(
                    forkSessionResult = Session(id = "ses-3", title = "Original (fork #1)", directory = "/repo"),
                    updateSessionError = SessionError.Http(500, "boom"),
                )
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    composerDraftStore = draftStore,
                )
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.forkFromMessage(messageId = "msg-2", messageText = "Try again")
            viewModel.confirmFork()
            advanceUntilIdle()
            viewModel.confirmFork()
            advanceUntilIdle()

            assertEquals(1, sessionRepository.forkSessionCallCount)
            assertFalse(viewModel.uiState.value.forkDialogVisible)
            assertFalse(viewModel.uiState.value.forkSubmitting)
            assertTrue(viewModel.uiState.value.forkResultVisible)
            assertEquals("Original (fork #1)", viewModel.uiState.value.forkedSessionTitle)
            val snackbar = requireNotNull(viewModel.uiState.value.snackbar)
            assertEquals("Fork created, but it couldn't be renamed.", snackbar.message)
            assertEquals(SessionChatSnackbarTone.Error, snackbar.tone)
            assertEquals(
                "Try again",
                draftStore
                    .load(
                        ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                        "/repo",
                        "ses-3",
                    )?.text,
            )

            viewModel.chooseForkOpen()
            runCurrent()
            assertEquals(
                ChildSessionNavigationTarget(sessionId = "ses-3", sessionTitle = "Original (fork #1)", worktree = "/repo"),
                viewModel.uiState.value.pendingSessionReplace,
            )
        }

    @Test
    fun forkFromMessage_forkFailure_keepsDialogOpenWithoutDraft() =
        runTest(testDispatcher) {
            val draftStore =
                dev.opencode.mobile.domain.composer
                    .InMemoryComposerDraftStore()
            val sessionRepository = FakeSessionRepository(forkSessionError = SessionError.Http(500, "boom"))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    composerDraftStore = draftStore,
                )
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.forkFromMessage(messageId = "msg-2", messageText = "Try again")
            viewModel.confirmFork()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertFalse(viewModel.uiState.value.forkSubmitting)
            assertFalse(viewModel.uiState.value.forkResultVisible)
            assertNotNull(viewModel.uiState.value.forkErrorMessage)
            assertNull(sessionRepository.updateRequest)
            assertNull(
                draftStore.load(
                    ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                    "/repo",
                    "forked-ses",
                ),
            )
        }

    @Test
    fun forkDialog_cancelOrBlankTitle_makesNoRepositoryCalls() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Original", "/repo")
            advanceUntilIdle()

            viewModel.forkFromMessage(messageId = "msg-2", messageText = "Try again")
            viewModel.cancelForkDialog()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.forkDialogVisible)

            viewModel.openForkSession()
            viewModel.updateForkDraft("   ")
            viewModel.confirmFork()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.forkDialogVisible)
            assertEquals("Enter a session title", viewModel.uiState.value.forkErrorMessage)
            assertEquals(0, sessionRepository.forkSessionCallCount)
            assertNull(sessionRepository.updateRequest)
        }

    @Test
    fun runClientSlashAction_undo_revertsLastUndoableMessageAndRestoresComposer() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                    Message(id = "msg-3", role = MessageRole.User, text = "Second"),
                )
            val sessionRepository = FakeSessionRepository()
            val commandRepository = FakeCommandRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                    commandRepository = commandRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(ClientSlashAction.Undo in viewModel.uiState.value.visibleClientSlashActions)

            viewModel.runClientSlashAction(ClientSlashAction.Undo)
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.RevertRequest(id = "ses-1", messageId = "msg-3", partId = null, directory = "/repo"),
                sessionRepository.revertRequest,
            )
            assertEquals("Second", viewModel.uiState.value.composer.text)
            assertEquals(0, sessionRepository.abortCallCount)
            assertFalse(commandRepository.runCommandCalled)
            assertTrue(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun runClientSlashAction_undo_whenBusy_abortsBeforeReverting() =
        runTest(testDispatcher) {
            val messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "First"))
            val sessionRepository = FakeSessionRepository(sessionStatusType = "busy")
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sessionStatusLabel)

            viewModel.runClientSlashAction(ClientSlashAction.Undo)
            advanceUntilIdle()

            assertEquals(1, sessionRepository.abortCallCount)
            assertEquals("msg-1", sessionRepository.revertRequest?.messageId)
        }

    @Test
    fun revertMessage_revertsThatUserTurnAndRestoresComposer() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                    Message(id = "msg-3", role = MessageRole.User, text = "Second"),
                )
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.RevertRequest(id = "ses-1", messageId = "msg-1", partId = null, directory = "/repo"),
                sessionRepository.revertRequest,
            )
            assertEquals("First", viewModel.uiState.value.composer.text)
            assertEquals(0, sessionRepository.abortCallCount)
            assertTrue(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun sendWithPendingRevert_commitsRevertBeforePrompt() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val repository = FakeMessageRepository(messages = messages)
            val sessionRepository = FakeSessionRepository()
            var promptSentBeforeCommit: Boolean? = null
            sessionRepository.onCommitRevert = { promptSentBeforeCommit = repository.sendPromptAsyncCalled }
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.revertMessage("msg-1")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            viewModel.updateComposerText("Edited")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(
                listOf(FakeSessionRepository.LifecycleRequest(id = "ses-1", directory = "/repo")),
                sessionRepository.commitRevertRequests,
            )
            assertEquals(false, promptSentBeforeCommit)
            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("Edited", repository.sentOptions?.text)
            assertFalse(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun sendWithoutPendingRevert_doesNotCommit() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("Hello")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertTrue(sessionRepository.commitRevertRequests.isEmpty())
        }

    @Test
    fun failedRevertCommit_keepsPromptUnsentAndReportsError() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val repository = FakeMessageRepository(messages = messages)
            val sessionRepository =
                FakeSessionRepository(
                    commitRevertError = SessionError.Http(statusCode = 409, message = "Session is busy"),
                )
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.revertMessage("msg-1")
            advanceUntilIdle()

            viewModel.updateComposerText("Edited")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals(1, sessionRepository.commitRevertRequests.size)
            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals("Edited", viewModel.uiState.value.composer.text)
            assertEquals(
                "Session is busy",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertTrue(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun revertStagedElsewhere_hidesRevertedTurnsUntilCleared() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                    Message(id = "msg-3", role = MessageRole.User, text = "Second"),
                    Message(id = "msg-4", role = MessageRole.Assistant, text = "Reply 2"),
                )
            val repository = FakeMessageRepository(messages = messages)
            val viewModel = createViewModel(repository = repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            fun shows(id: String) =
                viewModel.uiState.value.messages
                    .any { it.id.contains(id) }
            assertTrue(shows("msg-3"))

            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.revert.staged",
                    sessionId = "ses-1",
                    rawProperties = buildJsonObject { put("revert", buildJsonObject { put("messageID", "msg-3") }) },
                ),
            )
            advanceUntilIdle()
            assertTrue(shows("msg-1"))
            assertTrue(shows("msg-2"))
            assertFalse(shows("msg-3"))
            assertFalse(shows("msg-4"))
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            // A v2 rename (projected as a partial session.updated) keeps the staged revert.
            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.updated",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "info",
                                buildJsonObject {
                                    put("id", "ses-1")
                                    put("sessionID", "ses-1")
                                    put("title", "Renamed")
                                },
                            )
                        },
                ),
            )
            advanceUntilIdle()
            assertFalse(shows("msg-3"))
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            repository.eventStream.emit(OpencodeEvent(type = "session.revert.cleared", sessionId = "ses-1"))
            advanceUntilIdle()
            assertTrue(shows("msg-3"))
            assertTrue(shows("msg-4"))
            assertFalse(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun v1SessionUpdatedRevertFromElsewhere_showsPendingRevert() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val repository = FakeMessageRepository(messages = messages)
            val viewModel = createViewModel(repository = repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.updated",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "info",
                                buildJsonObject {
                                    put("id", "ses-1")
                                    put("title", "Title")
                                    put("time", buildJsonObject { put("created", 1) })
                                    put("revert", buildJsonObject { put("messageID", "msg-2") })
                                },
                            )
                        },
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingRevert)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.id.contains("msg-2") },
            )
        }

    @Test
    fun cancelRevert_unrevertsAndClearsComposer() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            viewModel.cancelRevert()
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.LifecycleRequest(id = "ses-1", directory = "/repo"),
                sessionRepository.unrevertRequest,
            )
            assertEquals("", viewModel.uiState.value.composer.text)
            assertFalse(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun cancelRevert_withoutPendingRevert_isNoop() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.cancelRevert()
            advanceUntilIdle()

            assertEquals(null, sessionRepository.unrevertRequest)
        }

    @Test
    fun sendPrompt_afterRevert_clearsPendingRevertChip() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val sessionRepository = FakeSessionRepository()
            val repository = FakeMessageRepository(messages = messages)
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            viewModel.updateComposerText("Changed")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertFalse(viewModel.uiState.value.hasPendingRevert)
            assertEquals(null, sessionRepository.unrevertRequest)
        }

    @Test
    fun sendPrompt_failure_keepsPendingRevertChip() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                )
            val sessionRepository = FakeSessionRepository()
            val repository = FakeMessageRepository(messages = messages, shouldFailSend = true)
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingRevert)

            viewModel.updateComposerText("Changed")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertTrue(viewModel.uiState.value.hasPendingRevert)
            assertEquals(null, sessionRepository.unrevertRequest)
        }

    @Test
    fun revertMessage_whenBusy_abortsBeforeReverting() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.User, text = "Second"),
                )
            val sessionRepository = FakeSessionRepository(sessionStatusType = "busy")
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sessionStatusLabel)

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()

            assertEquals(1, sessionRepository.abortCallCount)
            assertEquals("msg-1", sessionRepository.revertRequest?.messageId)
            assertEquals("First", viewModel.uiState.value.composer.text)
        }

    @Test
    fun revertMessage_unknownMessage_isNoop() =
        runTest(testDispatcher) {
            val messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "First"))
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("missing")
            advanceUntilIdle()

            assertEquals(null, sessionRepository.revertRequest)
            assertEquals("", viewModel.uiState.value.composer.text)
        }

    @Test
    fun revertMessage_attachmentOnly_revertsThatTurn() =
        runTest(testDispatcher) {
            val file =
                MessagePart.File(
                    id = "file-1",
                    filename = "notes.txt",
                    mime = "text/plain",
                    kind = AttachmentKind.Text,
                    dataUrl = "data:text/plain;base64,SGk=",
                )
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(id = "msg-1", role = MessageRole.User, text = "", parts = listOf(file)),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()

            assertEquals("msg-1", sessionRepository.revertRequest?.messageId)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertTrue(viewModel.uiState.value.hasPendingRevert)
        }

    @Test
    fun revertMessage_afterEarlierRevert_ignoresLaterStillVisibleTurn() =
        runTest(testDispatcher) {
            val messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "First"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = "Reply"),
                    Message(id = "msg-3", role = MessageRole.User, text = "Second"),
                )
            val sessionRepository = FakeSessionRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.revertMessage("msg-1")
            advanceUntilIdle()
            assertEquals("msg-1", sessionRepository.revertRequest?.messageId)

            viewModel.revertMessage("msg-3")
            advanceUntilIdle()

            assertEquals("msg-1", sessionRepository.revertRequest?.messageId)
            assertEquals("First", viewModel.uiState.value.composer.text)
        }

    @Test
    fun abort_interruptsBoundSessionAndRunningSubagentChild() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(sessionStatusType = "busy")
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(
                                        id = "msg-1",
                                        role = MessageRole.Assistant,
                                        text = "",
                                        parts =
                                            listOf(
                                                MessagePart.Tool(
                                                    toolName = "subagent",
                                                    status = ToolStatus.Running,
                                                    id = "prt-1",
                                                    input =
                                                        buildJsonObject {
                                                            put("agent", "explore")
                                                            put("description", "Find git projects")
                                                        },
                                                    metadata =
                                                        buildJsonObject {
                                                            put("sessionID", "ses-child")
                                                        },
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages.any { message ->
                    message.blocks.any { block ->
                        block is SessionChatTimelineBlock.ChildSessionRow &&
                            block.childSessionId == "ses-child"
                    }
                },
            )

            viewModel.abort()
            advanceUntilIdle()

            assertEquals(setOf("ses-1", "ses-child"), sessionRepository.abortSessionIds.toSet())
        }

    @Test
    fun runningSubagentLoadedWithoutChildId_linksChildSessionByDescription() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionStatusType = "busy",
                    childSessions =
                        listOf(
                            Session(
                                id = "ses-child",
                                title = "Find git projects",
                                parentId = "ses-1",
                                createdAtEpochMs = 1L,
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(
                                        id = "msg-1",
                                        role = MessageRole.Assistant,
                                        text = "",
                                        parts =
                                            listOf(
                                                MessagePart.Tool(
                                                    toolName = "subagent",
                                                    status = ToolStatus.Running,
                                                    id = "prt-1",
                                                    input =
                                                        buildJsonObject {
                                                            put("agent", "general")
                                                            put("description", "Find git projects")
                                                        },
                                                    metadata = buildJsonObject {},
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages.any { message ->
                    message.blocks.any { block ->
                        block is SessionChatTimelineBlock.ChildSessionRow &&
                            block.childSessionId == "ses-child"
                    }
                },
            )
            assertEquals(listOf("ses-1"), sessionRepository.childSessionRequests)
        }

    @Test
    fun childSessionLookupFailure_retriesAndLinks() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionStatusType = "busy",
                    childSessions =
                        listOf(
                            Session(
                                id = "ses-child",
                                title = "Find git projects",
                                parentId = "ses-1",
                                createdAtEpochMs = 1L,
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(
                                        id = "msg-1",
                                        role = MessageRole.Assistant,
                                        text = "",
                                        parts =
                                            listOf(
                                                MessagePart.Tool(
                                                    toolName = "subagent",
                                                    status = ToolStatus.Running,
                                                    id = "prt-1",
                                                    input =
                                                        buildJsonObject {
                                                            put("agent", "general")
                                                            put("description", "Find git projects")
                                                        },
                                                    metadata = buildJsonObject {},
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    sessionRepository = sessionRepository,
                )
            sessionRepository.childSessionFailuresRemaining = 1
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages.any { message ->
                    message.blocks.any { block ->
                        block is SessionChatTimelineBlock.ChildSessionRow &&
                            block.childSessionId == "ses-child"
                    }
                },
            )
            assertEquals(listOf("ses-1", "ses-1"), sessionRepository.childSessionRequests)
        }

    @Test
    fun abort_parentSuccessChildFailure_clearsWorking() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionStatusType = "busy",
                    abortErrorsById =
                        mapOf("ses-child" to SessionError.Http(statusCode = 500, message = "child abort failed")),
                )
            val repository =
                FakeMessageRepository(
                    messages =
                        listOf(
                            Message(
                                id = "msg-1",
                                role = MessageRole.Assistant,
                                text = "",
                                parts =
                                    listOf(
                                        MessagePart.Tool(
                                            toolName = "subagent",
                                            status = ToolStatus.Running,
                                            id = "prt-1",
                                            input =
                                                buildJsonObject {
                                                    put("agent", "explore")
                                                    put("description", "Find git projects")
                                                },
                                            metadata =
                                                buildJsonObject {
                                                    put("sessionID", "ses-child")
                                                },
                                        ),
                                    ),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals("Working", viewModel.uiState.value.sessionStatusLabel)
            assertEquals("Working", viewModel.uiState.value.workingIndicatorLabel)

            viewModel.abort()
            runCurrent()
            repository.eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()

            assertEquals(setOf("ses-1", "ses-child"), sessionRepository.abortSessionIds.toSet())
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)
        }

    @Test
    fun runClientSlashAction_redo_unrevertsAndClearsComposerRestore() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    getSessionResult = Session(id = "ses-1", title = "Title", revert = SessionRevert(messageId = "msg-1")),
                )
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(ClientSlashAction.Redo in viewModel.uiState.value.visibleClientSlashActions)
            viewModel.restoreComposerText("Second")

            viewModel.runClientSlashAction(ClientSlashAction.Redo)
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.LifecycleRequest(id = "ses-1", directory = "/repo"),
                sessionRepository.unrevertRequest,
            )
            assertEquals("", viewModel.uiState.value.composer.text)
        }

    @Test
    fun runClientSlashAction_redo_withoutActiveRevert_isNoop() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val viewModel = createViewModel(repository = FakeMessageRepository(), sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Redo)
            advanceUntilIdle()

            assertEquals(null, sessionRepository.unrevertRequest)
        }

    @Test
    fun runClientSlashAction_compact_summarizesWithSelectedModelAndRefreshes() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            val commandRepository = FakeCommandRepository()
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                    commandRepository = commandRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(ClientSlashAction.Compact in viewModel.uiState.value.visibleClientSlashActions)

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()

            assertEquals(
                FakeSessionRepository.SummarizeRequest(
                    id = "ses-1",
                    providerId = "anthropic",
                    modelId = "claude-sonnet",
                    directory = "/repo",
                ),
                sessionRepository.summarizeRequest,
            )
            assertEquals(
                SessionCompactChrome.FALLBACK_SUCCESS,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Default,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertFalse(commandRepository.runCommandCalled)
        }

    @Test
    fun runClientSlashAction_compact_showsCompactingWhileInFlight() =
        runTest(testDispatcher) {
            val hold = CompletableDeferred<Unit>()
            val sessionRepository = FakeSessionRepository(summarizeHoldUntil = hold)
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()

            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.sessionStatusLabel)
            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.workingIndicatorLabel)

            hold.complete(Unit)
            runCurrent()

            assertEquals(
                SessionCompactChrome.FALLBACK_SUCCESS,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertEquals(WorkingTurnIndicator.IDLE_LABEL, viewModel.uiState.value.workingIndicatorLabel)
            assertFalse(viewModel.uiState.value.workingIndicatorCollapsed)
            assertFalse(viewModel.uiState.value.workingIndicatorFading)

            testDispatcher.scheduler.advanceTimeBy(WorkingTurnIndicator.IDLE_HOLD_MS)
            runCurrent()
            assertEquals(WorkingTurnIndicator.IDLE_LABEL, viewModel.uiState.value.workingIndicatorLabel)
            assertTrue(viewModel.uiState.value.workingIndicatorCollapsed)
        }

    @Test
    fun runClientSlashAction_compactEnqueued_staysCompactingUntilCompactionCompletes() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository = FakeSessionRepository(compactStart = CompactionStart.Enqueued)
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()

            // POST /compact returned (enqueued): no success yet, still Compacting….
            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)

            repository.messages =
                repository.messages +
                Message(
                    id = "msg-c",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            MessagePart.Compaction(id = "msg-c", completed = true, summary = "## Goal\nShip it"),
                        ),
                    mode = "compaction",
                    sentAtEpochMs = 5,
                )
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertEquals(
                SessionCompactChrome.FALLBACK_SUCCESS,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun runClientSlashAction_compactEnqueued_failedCompactionShowsNoSuccess() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sessionRepository = FakeSessionRepository(compactStart = CompactionStart.Enqueued)
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()
            repository.messages =
                repository.messages +
                Message(
                    id = "msg-c",
                    role = MessageRole.Assistant,
                    text = "",
                    parts = listOf(MessagePart.Compaction(id = "msg-c", completed = true)),
                    mode = "compaction",
                    sentAtEpochMs = 5,
                )
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)
        }

    @Test
    fun runClientSlashAction_compactEnqueued_timelineCompletesBeforePostReturns_showsOneSuccess() =
        runTest(testDispatcher) {
            val hold = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository()
            val sessionRepository =
                FakeSessionRepository(summarizeHoldUntil = hold, compactStart = CompactionStart.Enqueued)
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()
            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.sessionStatusLabel)

            // The live compaction item completes while POST /compact is still in flight.
            repository.messages = repository.messages + completedCompactionMessage(summary = "## Goal\nShip it")
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)

            hold.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertEquals(
                SessionCompactChrome.FALLBACK_SUCCESS,
                viewModel.uiState.value.snackbar
                    ?.message,
            )

            viewModel.consumeSnackbar()
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.snackbar)
        }

    @Test
    fun runClientSlashAction_compactEnqueued_busyThenIdleBeforePostReturns_clearsWithoutSuccess() =
        runTest(testDispatcher) {
            val hold = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository()
            val sessionRepository =
                FakeSessionRepository(summarizeHoldUntil = hold, compactStart = CompactionStart.Enqueued)
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()
            repository.eventStream.emit(busyStatusEvent())
            advanceUntilIdle()
            repository.eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceUntilIdle()
            // Enqueue not confirmed yet: idle alone does not end the compaction.
            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.sessionStatusLabel)

            hold.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)
        }

    @Test
    fun runClientSlashAction_compactFinished_timelineCompletesFirst_showsOneSuccess() =
        runTest(testDispatcher) {
            val hold = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository()
            val sessionRepository =
                FakeSessionRepository(summarizeHoldUntil = hold, compactStart = CompactionStart.Finished)
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()
            repository.messages = repository.messages + completedCompactionMessage(summary = "## Goal\nShip it")
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)

            hold.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                SessionCompactChrome.FALLBACK_SUCCESS,
                viewModel.uiState.value.snackbar
                    ?.message,
            )

            viewModel.consumeSnackbar()
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.snackbar)
            assertNull(viewModel.uiState.value.sessionStatusLabel)
        }

    @Test
    fun runClientSlashAction_compactEnqueued_stillBusyWhenPostReturns_staysCompacting() =
        runTest(testDispatcher) {
            val hold = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository()
            val sessionRepository =
                FakeSessionRepository(summarizeHoldUntil = hold, compactStart = CompactionStart.Enqueued)
            val viewModel = createViewModel(repository = repository, sessionRepository = sessionRepository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()
            repository.eventStream.emit(busyStatusEvent())
            advanceUntilIdle()

            // The post-enqueue re-check must see the session still busy, not treat it as idle.
            hold.complete(Unit)
            advanceUntilIdle()
            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.sessionStatusLabel)
            assertNull(viewModel.uiState.value.snackbar)

            repository.messages = repository.messages + completedCompactionMessage(summary = "## Goal\nShip it")
            repository.eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceUntilIdle()
            repository.eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.sessionStatusLabel)
            assertEquals(
                SessionCompactChrome.FALLBACK_SUCCESS,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    private fun completedCompactionMessage(summary: String?): Message =
        Message(
            id = "msg-c",
            role = MessageRole.Assistant,
            text = "",
            parts = listOf(MessagePart.Compaction(id = "msg-c", completed = true, summary = summary)),
            mode = "compaction",
            sentAtEpochMs = 5,
        )

    private fun busyStatusEvent(): OpencodeEvent =
        OpencodeEvent(
            type = "session.status",
            sessionId = "ses-1",
            rawProperties = buildJsonObject { put("type", "busy") },
        )

    @Test
    fun workingFooter_holdsThroughIdleThenCollapsesAndCancelsOnBusy() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel =
                createViewModel(
                    repository = repository,
                    sessionRepository = FakeSessionRepository(sessionStatusType = "busy"),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals("Working", viewModel.uiState.value.workingIndicatorLabel)
            assertFalse(viewModel.uiState.value.workingIndicatorCollapsed)
            assertFalse(viewModel.uiState.value.workingIndicatorFading)

            repository.eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()

            assertEquals(WorkingTurnIndicator.IDLE_LABEL, viewModel.uiState.value.workingIndicatorLabel)
            assertFalse(viewModel.uiState.value.workingIndicatorFading)
            assertFalse(viewModel.uiState.value.workingIndicatorCollapsed)
            assertNull(viewModel.uiState.value.sessionStatusLabel)

            testDispatcher.scheduler.advanceTimeBy(WorkingTurnIndicator.IDLE_HOLD_MS - 1)
            runCurrent()
            assertEquals(WorkingTurnIndicator.IDLE_LABEL, viewModel.uiState.value.workingIndicatorLabel)
            assertFalse(viewModel.uiState.value.workingIndicatorFading)
            assertFalse(viewModel.uiState.value.workingIndicatorCollapsed)

            testDispatcher.scheduler.advanceTimeBy(1)
            runCurrent()
            assertEquals(WorkingTurnIndicator.IDLE_LABEL, viewModel.uiState.value.workingIndicatorLabel)
            assertFalse(viewModel.uiState.value.workingIndicatorFading)
            assertTrue(viewModel.uiState.value.workingIndicatorCollapsed)
            assertNull(viewModel.uiState.value.sessionStatusLabel)

            repository.eventStream.emit(
                OpencodeEvent(
                    type = "session.status",
                    sessionId = "ses-1",
                    rawProperties = buildJsonObject { put("type", "busy") },
                ),
            )
            runCurrent()

            assertEquals("Working", viewModel.uiState.value.workingIndicatorLabel)
            assertFalse(viewModel.uiState.value.workingIndicatorFading)
            assertFalse(viewModel.uiState.value.workingIndicatorCollapsed)
        }

    @Test
    fun runClientSlashAction_compact_failureClearsCompactingAndShowsError() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(summarizeError = RuntimeException("boom"))
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(),
                    sessionRepository = sessionRepository,
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.runClientSlashAction(ClientSlashAction.Compact)
            advanceUntilIdle()

            assertEquals(
                SessionCompactChrome.FAILURE,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertNull(viewModel.uiState.value.sessionStatusLabel)
        }

    @Test
    fun remoteCompaction_busyWithCompactionPart_showsCompactingWithoutLocalFlag() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(
                                        id = "u1",
                                        role = MessageRole.User,
                                        text = "",
                                        parts = listOf(MessagePart.Compaction(id = "c1", auto = false)),
                                        model = null,
                                        mode = null,
                                        sentAtEpochMs = 1,
                                    ),
                                ),
                        ),
                    sessionRepository = FakeSessionRepository(sessionStatusType = "busy"),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.sessionStatusLabel)
            assertEquals(SessionCompactChrome.STATUS_LABEL, viewModel.uiState.value.workingIndicatorLabel)
        }

    @Test
    fun remoteCompaction_busyWithCompletedSnapshot_doesNotShowCompacting() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    repository =
                        FakeMessageRepository(
                            messages =
                                listOf(
                                    Message(
                                        id = "u1",
                                        role = MessageRole.User,
                                        text = "hello",
                                        mode = "build",
                                        sentAtEpochMs = 1,
                                    ),
                                    Message(
                                        id = "c1",
                                        role = MessageRole.Assistant,
                                        text = "summary",
                                        parts =
                                            listOf(
                                                MessagePart.Compaction(id = "c1", auto = false, completed = true),
                                            ),
                                        mode = "compaction",
                                        sentAtEpochMs = 2,
                                    ),
                                ),
                        ),
                    sessionRepository = FakeSessionRepository(sessionStatusType = "busy"),
                )
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals("Working", viewModel.uiState.value.sessionStatusLabel)
            assertEquals("Working", viewModel.uiState.value.workingIndicatorLabel)
        }

    @Test
    fun sendPrompt_whileConnecting_queuesUntilLive() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val liveUpdates =
                FixedLiveUpdates(
                    LiveUpdatesChrome(
                        label = LiveUpdatesLabel.Connecting,
                        canMutate = false,
                        composerEnabled = true,
                    ),
                )
            val viewModel = createViewModel(repository, liveUpdates = liveUpdates)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.composerEnabled)
            assertFalse(viewModel.uiState.value.canMutate)

            viewModel.updateComposerText("Queued prompt")
            viewModel.sendPrompt()
            runCurrent()

            assertFalse(repository.sendPromptAsyncCalled)
            assertTrue(viewModel.uiState.value.isSending)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.text == "Queued prompt" && it.isPending },
            )

            viewModel.updateComposerText("Second")
            viewModel.sendPrompt()
            runCurrent()
            assertFalse(repository.sendPromptAsyncCalled)

            liveUpdates.set(
                LiveUpdatesChrome(
                    label = LiveUpdatesLabel.Live,
                    canMutate = true,
                    composerEnabled = true,
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.sendPromptAsyncCalled)
            assertEquals("Queued prompt", repository.sentOptions?.text)
            assertEquals(false, viewModel.uiState.value.isSending)
        }

    @Test
    fun sendPrompt_whileConnecting_timesOutAfter8s() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val liveUpdates =
                FixedLiveUpdates(
                    LiveUpdatesChrome(
                        label = LiveUpdatesLabel.Connecting,
                        canMutate = false,
                        composerEnabled = true,
                    ),
                )
            val viewModel = createViewModel(repository, liveUpdates = liveUpdates)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.updateComposerText("Queued prompt")
            viewModel.sendPrompt()
            advanceTimeBy(SessionChatViewModel.PENDING_SEND_TIMEOUT_MS)
            advanceUntilIdle()

            assertFalse(repository.sendPromptAsyncCalled)
            assertEquals(false, viewModel.uiState.value.isSending)
            assertEquals(
                SessionChatComposer.PENDING_SEND_TIMEOUT_ERROR,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertEquals(
                SessionChatSnackbarTone.Error,
                viewModel.uiState.value.snackbar
                    ?.tone,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .none { it.text == "Queued prompt" },
            )
            assertNull(viewModel.uiState.value.sessionStatusLabel)
        }

    @Test
    fun secondViewModelForSameProfileAndWorktree_showsLastSelectionBeforeAnyFetchResolves() =
        runTest(testDispatcher) {
            val (models, store) = sharedCatalog()
            val first = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            first.selectModel(gpt4Option)
            advanceUntilIdle()

            models.gate = CompletableDeferred()
            val callsBefore = models.calls
            val second =
                createViewModel(
                    FakeMessageRepository(messages = emptyList()),
                    modelRepository = models,
                    modelCatalogStore = store,
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(loadGate = CompletableDeferred()),
                )
            second.bind("ses-1", "Title", "/repo")
            runCurrent()

            assertEquals(callsBefore + 1, models.calls)
            assertEquals(true, second.uiState.value.composer.isModelSelectionReady)
            assertEquals(gpt4Option.modelLabel, second.uiState.value.composer.selectedModelLabel)
        }

    @Test
    fun cachedCatalog_neverOpenedSessionWaitsForHistoryInsteadOfShowingTheServerDefault() =
        runTest(testDispatcher) {
            val (models, store) = sharedCatalog()
            val first = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            first.bind("ses-0", "Other", "/repo")
            advanceUntilIdle()

            val persistedGate = CompletableDeferred<Unit>()
            val callsBefore = models.calls
            models.gate = CompletableDeferred()
            val second =
                createViewModel(
                    FakeMessageRepository(messages = listOf(gpt4UserMessage())),
                    modelRepository = models,
                    modelCatalogStore = store,
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(loadGate = persistedGate),
                )
            val shownModels = mutableListOf<String?>()
            backgroundScope.launch {
                second.uiState.collect { state ->
                    val shown =
                        state.composer.selectedModel
                            ?.option
                            ?.modelId
                    if (state.composer.isModelSelectionReady && shownModels.lastOrNull() != shown) shownModels += shown
                }
            }
            second.bind("ses-1", "Title", "/repo")
            runCurrent()

            assertEquals(false, second.uiState.value.composer.isModelSelectionReady)
            assertNull(second.uiState.value.composer.selectedModel)
            assertEquals(listOf(sonnetOption, gpt4Option), second.uiState.value.composer.modelOptions)

            persistedGate.complete(Unit)
            runCurrent()

            // The catalog came from the store; the gated refresh did not hold the selection back.
            assertEquals(callsBefore + 1, models.calls)
            assertEquals(true, second.uiState.value.composer.isModelSelectionReady)
            assertEquals(listOf<String?>("gpt-4"), shownModels)
            models.gate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf<String?>("gpt-4"), shownModels)
        }

    @Test
    fun cachedCatalog_failedRefreshKeepsTheSelection() =
        runTest(testDispatcher) {
            val (models, store) = sharedCatalog()
            val first = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            first.selectModel(gpt4Option)
            advanceUntilIdle()

            models.error = ModelSelectionError.Transport(IllegalStateException("offline"))
            val second = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            second.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(true, second.uiState.value.composer.isModelSelectionReady)
            assertNull(second.uiState.value.composer.modelCatalogError)
            assertEquals(
                "gpt-4",
                second.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun cachedCatalog_newerUserHistoryHintReplacesTheProvisionalSelection() =
        runTest(testDispatcher) {
            val (models, store) = sharedCatalog()
            val first = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            first.selectModel(gpt4Option)
            advanceUntilIdle()

            val persistedGate = CompletableDeferred<Unit>()
            val second =
                createViewModel(
                    FakeMessageRepository(messages = listOf(newerSonnetUserMessage())),
                    modelRepository = models,
                    modelCatalogStore = store,
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(loadGate = persistedGate),
                )
            second.bind("ses-1", "Title", "/repo")
            runCurrent()
            assertEquals(true, second.uiState.value.composer.isModelSelectionReady)
            assertEquals(
                "gpt-4",
                second.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )

            persistedGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                "claude-sonnet",
                second.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun cachedCatalog_userPickBeforeReconcileIsKept() =
        runTest(testDispatcher) {
            val (models, store) = sharedCatalog()
            val first = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val persistedGate = CompletableDeferred<Unit>()
            val second =
                createViewModel(
                    FakeMessageRepository(messages = listOf(newerSonnetUserMessage())),
                    modelRepository = models,
                    modelCatalogStore = store,
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(loadGate = persistedGate),
                )
            second.bind("ses-1", "Title", "/repo")
            runCurrent()
            second.selectModel(gpt4Option)
            persistedGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                "gpt-4",
                second.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
        }

    @Test
    fun profileSwitch_emptiesTheCatalogCache() =
        runTest(testDispatcher) {
            var profile = ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", id = "a")
            val models = FakeModelRepository(ModelSelectionCatalog(listOf(sonnetOption, gpt4Option), sonnetOption))
            val store = testModelCatalogStore(models, currentProfile = { profile })
            val first = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            first.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            assertEquals(true, first.uiState.value.composer.isModelSelectionReady)

            profile = profile.copy(id = "b", baseUrl = "http://10.0.0.2:4096/")
            store.clear()
            models.gate = CompletableDeferred()
            val second = createViewModel(FakeMessageRepository(messages = emptyList()), modelRepository = models, modelCatalogStore = store)
            second.bind("ses-1", "Title", "/repo")
            runCurrent()

            assertEquals(false, second.uiState.value.composer.isModelSelectionReady)
            assertEquals("Loading model…", second.uiState.value.composer.selectedModelLabel)
        }

    @Test
    fun refreshedCatalogWithoutTheSelectedModel_fallsBackWithNotice() =
        runTest(testDispatcher) {
            val (models, store) = sharedCatalog()
            val repository = FakeMessageRepository(messages = emptyList())
            val viewModel = createViewModel(repository, modelRepository = models, modelCatalogStore = store)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.selectModel(gpt4Option)
            advanceUntilIdle()

            models.enqueue(ModelSelectionCatalog(listOf(sonnetOption), sonnetOption))
            repository.eventStream.emit(OpencodeEvent(type = "model.updated"))
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.composer.isModelSelectionReady)
            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.composer.selectedModel
                    ?.option
                    ?.modelId,
            )
            assertEquals(
                "Selected model is unavailable. Using ${sonnetOption.modelLabel}.",
                viewModel.uiState.value.composer.modelNotice,
            )
        }

    @Test
    fun dictationInsert_splicesTranscriptAtCaret() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.dictationInsert()
            advanceUntilIdle()

            val composer = viewModel.uiState.value.composer
            assertEquals("Hello big there", composer.text)
            assertEquals(9, composer.cursor)
            assertNull(viewModel.uiState.value.dictation)
        }

    @Test
    fun dictationCancel_afterPartial_leavesComposerUnchanged() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.dictationCancel()
            advanceUntilIdle()

            val composer = viewModel.uiState.value.composer
            assertEquals("Hello there", composer.text)
            assertEquals(5, composer.cursor)
            assertNull(viewModel.uiState.value.dictation)
        }

    @Test
    fun dictationCancelForModeChangeAndBackground_doesNotInsert() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.cancelDictationForModeChange()
            advanceUntilIdle()

            assertEquals("Hello there", viewModel.uiState.value.composer.text)
            assertEquals(5, viewModel.uiState.value.composer.cursor)
            assertNull(viewModel.uiState.value.dictation)

            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.cancelDictationForBackground()
            advanceUntilIdle()

            assertEquals("Hello there", viewModel.uiState.value.composer.text)
            assertEquals(5, viewModel.uiState.value.composer.cursor)
            assertNull(viewModel.uiState.value.dictation)

            viewModel.dictationFinishDelayMs = 1_000
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.dictationInsert()
            viewModel.cancelDictationForBackground()
            advanceUntilIdle()

            assertEquals("Hello there", viewModel.uiState.value.composer.text)
            assertEquals(5, viewModel.uiState.value.composer.cursor)
            assertNull(viewModel.uiState.value.dictation)
        }

    @Test
    fun dictationInsert_emptyTranscript_keepsCard() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = "   ", volatile = "")
            viewModel.dictationInsert()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Hello there", state.composer.text)
            assertEquals(5, state.composer.cursor)
            assertEquals(ComposerDictationPhase.Listening, state.dictation?.phase)
        }

    @Test
    fun dictationModelDownload_updatesOnlyAModelCard() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            advanceUntilIdle()

            viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloading, 0.3f)
            assertNull(viewModel.uiState.value.dictation)

            viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled, languageLabel = "English")
            viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloading, 0.3f)
            val downloading = viewModel.uiState.value.dictation
            assertEquals(ComposerDictationPhase.ModelDownloading, downloading?.phase)
            assertEquals(0.3f, downloading?.downloadProgress)
            assertEquals("English", downloading?.languageLabel)

            viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloadQueued)
            assertEquals(ComposerDictationPhase.ModelDownloadQueued, viewModel.uiState.value.dictation?.phase)

            viewModel.dictationCancel()
            viewModel.dictationModelDownload(ComposerDictationPhase.ModelDownloadFailed)
            assertNull(viewModel.uiState.value.dictation)
        }

    @Test
    fun dictationUnavailable_duringFinishing_stillInserts() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.dictationFinishDelayMs = 1_000
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.dictationInsert()
            viewModel.beginDictationUnavailable(
                ComposerDictationPhase.RecognizerError,
                "late",
            )

            assertEquals(ComposerDictationPhase.Finishing, viewModel.uiState.value.dictation?.phase)
            assertEquals("Hello there", viewModel.uiState.value.composer.text)
            advanceUntilIdle()

            val composer = viewModel.uiState.value.composer
            assertEquals("Hello big there", composer.text)
            assertEquals(9, composer.cursor)
            assertNull(viewModel.uiState.value.dictation)
        }

    @Test
    fun dictationPartial_doesNotChangeComposerText() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "ger")

            val state = viewModel.uiState.value
            assertEquals("Hello there", state.composer.text)
            assertEquals(5, state.composer.cursor)
            assertEquals(" big", state.dictation?.committed)
            assertEquals("ger", state.dictation?.volatile)
        }

    @Test
    fun dictationStopped_keepsTranscriptRestsOrbAndStillInserts() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.updateComposerText("Hello there", cursor = 5)
            advanceUntilIdle()
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = " big", volatile = "")
            viewModel.dictationLevel(0.8f)
            viewModel.dictationStopped()

            val stopped = viewModel.uiState.value.dictation
            assertEquals(ComposerDictationPhase.Stopped, stopped?.phase)
            assertEquals(" big", stopped?.committed)
            assertEquals(0f, stopped?.level)

            // Nothing more arrives from a stopped recognizer, but stray callbacks are ignored.
            viewModel.dictationPartial(committed = " big extra", volatile = "")
            viewModel.dictationLevel(0.5f)
            val afterStray = viewModel.uiState.value.dictation
            assertEquals(" big", afterStray?.committed)
            assertEquals(0f, afterStray?.level)

            viewModel.dictationInsert()
            advanceUntilIdle()

            val composer = viewModel.uiState.value.composer
            assertEquals("Hello big there", composer.text)
            assertEquals(9, composer.cursor)
            assertNull(viewModel.uiState.value.dictation)
        }

    @Test
    fun dictationStopped_outsideListening_isIgnored() =
        runTest(testDispatcher) {
            val viewModel = dictationViewModel()
            viewModel.dictationStopped()
            assertNull(viewModel.uiState.value.dictation)

            viewModel.dictationFinishDelayMs = 1_000
            viewModel.beginDictation("English", onDevice = true)
            viewModel.dictationPartial(committed = "big", volatile = "")
            viewModel.dictationInsert()
            viewModel.dictationStopped()
            val finishing = viewModel.uiState.value.dictation
            assertEquals(ComposerDictationPhase.Finishing, finishing?.phase)
        }

    private fun TestScope.dictationViewModel(): SessionChatViewModel {
        val viewModel = createViewModel(FakeMessageRepository())
        viewModel.dictationFinishDelayMs = 0
        advanceUntilIdle()
        return viewModel
    }

    private val sonnetOption =
        ModelSelectionOption("anthropic", "Anthropic", "claude-sonnet", "Claude Sonnet", emptyList(), 128_000)
    private val gpt4Option =
        ModelSelectionOption("openai", "OpenAI", "gpt-4", "GPT-4", emptyList(), 128_000)

    private fun sharedCatalog(): Pair<FakeModelRepository, ModelCatalogStore> {
        val models = FakeModelRepository(ModelSelectionCatalog(listOf(sonnetOption, gpt4Option), sonnetOption))
        return models to testModelCatalogStore(models)
    }

    private fun gpt4UserMessage() =
        Message(
            id = "msg-gpt4",
            role = MessageRole.User,
            text = "Use GPT-4",
            modelProviderId = "openai",
            modelId = "gpt-4",
            sentAtEpochMs = 1_000L,
        )

    private fun newerSonnetUserMessage() =
        Message(
            id = "msg-newer",
            role = MessageRole.User,
            text = "Use Sonnet",
            modelProviderId = "anthropic",
            modelId = "claude-sonnet",
            sentAtEpochMs = Long.MAX_VALUE / 2,
        )

    @Test
    fun streamedUpload_showsProgress_blocksSend_thenSendsServerPath() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val uploads =
                FakeAttachmentUploads { onProgress ->
                    onProgress(50, 100)
                    gate.await()
                    AttachmentUploadResult.Uploaded("/tmp/uploads/u1/notes.txt")
                }
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository, attachmentUploads = uploads)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val file = stagedFile("notes.txt")

            viewModel.addAttachments(listOf(uploadCandidate("notes.txt", file)))
            runCurrent()

            val pending = viewModel.uiState.value.composer
            assertEquals(AttachmentUpload.Uploading(50), pending.attachments.single().upload)
            assertTrue(pending.hasPendingUploads)
            assertFalse(pending.canSend)

            gate.complete(Unit)
            runCurrent()

            val done = viewModel.uiState.value.composer
            assertEquals(AttachmentUpload.Uploaded("/tmp/uploads/u1/notes.txt"), done.attachments.single().upload)
            assertTrue(done.canSend)
            assertEquals(null, viewModel.uiState.value.snackbar)
            viewModel.sendPrompt()
            runCurrent()
            assertEquals(
                AttachmentUpload.Uploaded("/tmp/uploads/u1/notes.txt"),
                repository.sentOptions
                    ?.attachments
                    ?.single()
                    ?.upload,
            )
        }

    @Test
    fun nativeImageUnderLimit_staysInline_evenWhenUploadsAreAvailable() =
        runTest(testDispatcher) {
            val uploads = FakeAttachmentUploads { AttachmentUploadResult.TransportFailure }
            val viewModel = createViewModel(FakeMessageRepository(), attachmentUploads = uploads)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(listOf(testAttachment()))
            runCurrent()

            assertEquals(0, uploads.calls)
            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .single()
                    .isInline,
            )
        }

    @Test
    fun duplicateNameAndSize_isRefusedWithMessage() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(listOf(testAttachment(id = "a")))
            viewModel.addAttachments(listOf(testAttachment(id = "b")))
            runCurrent()

            assertEquals(
                listOf("a"),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
            assertEquals(
                "This file has already been uploaded",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun droppedFilesWithAFolder_attachTheFilesAndNameTheFolder() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val added =
                viewModel.addPastedOrDroppedAttachments(
                    AttachmentIngestionResult(listOf(testAttachment(id = "a")), errors = emptyList()),
                    skippedFolders = 1,
                )
            runCurrent()

            assertTrue(added)
            assertEquals(
                listOf("a"),
                viewModel.uiState.value.composer.attachments
                    .map { it.id },
            )
            assertEquals(
                "Folders can't be attached.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun droppedFolderOnly_addsNothingAndSaysWhy() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val added =
                viewModel.addPastedOrDroppedAttachments(AttachmentIngestionResult(emptyList(), emptyList()), skippedFolders = 1)
            runCurrent()

            assertFalse(added)
            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
            assertEquals(
                "Folders can't be attached.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun droppedDuplicate_keepsTheDuplicateMessageOverTheFolder() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            viewModel.addAttachments(listOf(testAttachment(id = "a")))

            val added =
                viewModel.addPastedOrDroppedAttachments(
                    AttachmentIngestionResult(listOf(testAttachment(id = "b")), errors = emptyList()),
                    skippedFolders = 1,
                )
            runCurrent()

            assertFalse(added)
            assertEquals(
                "This file has already been uploaded",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun pastedUnreadableItem_showsTheIngestionError() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val added =
                viewModel.addPastedOrDroppedAttachments(
                    AttachmentIngestionResult(emptyList(), listOf("The attachment could not be read.")),
                    skippedFolders = 0,
                )
            runCurrent()

            assertFalse(added)
            assertEquals(
                "The attachment could not be read.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun cancelledUpload_removesChipSilently() =
        runTest(testDispatcher) {
            val uploads = FakeAttachmentUploads { awaitCancellation() }
            val viewModel = createViewModel(FakeMessageRepository(), attachmentUploads = uploads)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()
            val file = stagedFile("big.zip")
            viewModel.addAttachments(listOf(uploadCandidate("big.zip", file, mime = "application/zip", kind = AttachmentKind.File)))
            runCurrent()

            viewModel.removeAttachment("upload-big.zip")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
            assertEquals(null, viewModel.uiState.value.snackbar)
            assertTrue(uploads.cancelled)
            assertFalse(file.exists())
        }

    @Test
    fun transportFailure_removesChipAndShowsUploadFailed_keepingUploadsOn() =
        runTest(testDispatcher) {
            val uploads = FakeAttachmentUploads { AttachmentUploadResult.TransportFailure }
            val viewModel = createViewModel(FakeMessageRepository(), attachmentUploads = uploads)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(listOf(uploadCandidate("notes.txt", stagedFile("notes.txt"))))
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
            assertEquals(
                "Upload failed",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
            assertFalse(uploads.contractMissed)
        }

    @Test
    fun contractMiss_fallsBackToDataUrlSilentlyAndClearsTheFlag() =
        runTest(testDispatcher) {
            val uploads = FakeAttachmentUploads { AttachmentUploadResult.ContractMiss }
            val viewModel = createViewModel(FakeMessageRepository(), attachmentUploads = uploads)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(listOf(uploadCandidate("notes.txt", stagedFile("notes.txt"))))
            advanceUntilIdle()

            assertTrue(uploads.contractMissed)
            assertEquals(null, viewModel.uiState.value.snackbar)
            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .single()
                    .isInline,
            )
        }

    @Test
    fun contractMiss_onFileOverTheInlineLimit_showsTheExistingSizeError() =
        runTest(testDispatcher) {
            val uploads = FakeAttachmentUploads { AttachmentUploadResult.ContractMiss }
            val viewModel = createViewModel(FakeMessageRepository(), attachmentUploads = uploads)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(
                listOf(
                    uploadCandidate("huge.txt", stagedFile("huge.txt"))
                        .copy(byteCount = AttachmentLimits.MAX_DECODED_BYTES + 1),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
            assertEquals(
                AttachmentLimits.TOO_LARGE_ERROR,
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    @Test
    fun genericFile_neverGoesInlineWhenUploadsAreUnavailable() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.addAttachments(listOf(uploadCandidate("data.zip", stagedFile("data.zip"), "application/zip", AttachmentKind.File)))
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.composer.attachments
                    .isEmpty(),
            )
            assertEquals(
                "Only images, PDFs, or text files can be attached here.",
                viewModel.uiState.value.snackbar
                    ?.message,
            )
        }

    private fun stagedFile(name: String): java.io.File {
        val directory =
            java.io
                .File(System.getProperty("java.io.tmpdir"), AttachmentLimits.STORE_DIRECTORY_NAME)
                .apply { mkdirs() }
        return java.io.File(directory, "${java.util.UUID.randomUUID()}-$name").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun uploadCandidate(
        name: String,
        file: java.io.File,
        mime: String = "text/plain",
        kind: AttachmentKind = AttachmentKind.Text,
    ): Attachment =
        Attachment(
            id = "upload-$name",
            filename = name,
            mime = mime,
            kind = kind,
            dataUrl = "",
            byteCount = 3,
            localPath = file.absolutePath,
        )

    private fun createViewModel(
        repository: FakeMessageRepository,
        modelRepository: FakeModelRepository = FakeModelRepository(),
        commandRepository: FakeCommandRepository = FakeCommandRepository(),
        agentRepository: FakeAgentRepository = FakeAgentRepository(),
        sessionModelSelectionStore: FakeSessionModelSelectionStore = FakeSessionModelSelectionStore(),
        composerDraftStore: dev.opencode.mobile.domain.composer.ComposerDraftStore =
            dev.opencode.mobile.domain.composer
                .InMemoryComposerDraftStore(),
        sessionRepository: FakeSessionRepository = FakeSessionRepository(),
        permissionLiveStore: PermissionLiveStore = FakePermissionLiveStore(emptyList()),
        questionLiveStore: QuestionLiveStore = FakeQuestionLiveStore(emptyList()),
        sessionReadyStore: SessionReadyStore = EmptySessionReadyStore,
        connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
        serverProtocol: ServerProtocol = ServerProtocol.V1,
        liveUpdates: LiveUpdates = FixedLiveUpdates(),
        catalogRetryDelaysMs: List<Long> = emptyList(),
        modelCatalogStore: ModelCatalogStore? = null,
        sessionViewReporter: SessionViewReporter = NoOpSessionViewReporter,
        projectFilesRepository: dev.opencode.mobile.domain.filesystem.ProjectFilesRepository =
            dev.opencode.mobile.ui.sessionchat.EmptyProjectFilesRepository,
        homeProjectsSnapshot: dev.opencode.mobile.domain.home.HomeProjectsSnapshot =
            dev.opencode.mobile.domain.home
                .HomeProjectsSnapshot(),
        worktreeRepository: WorktreeRepository = dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository,
        worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
        sideQuestionsSupported: Boolean = false,
        sideQuestionStore: SideQuestionStore? = null,
        attachmentUploads: AttachmentUploadService = UnavailableAttachmentUploadService,
    ): SessionChatViewModel {
        val eventStream = repository.eventStream
        val liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        val json = Json { ignoreUnknownKeys = true }
        val sessionStatusLiveStore =
            DefaultSessionStatusLiveStore(
                sessionRepository,
                eventStream,
                liveStoreScope,
                EventDomainAdapter(json),
                SilentWorldSignals,
                testSyncCoordinator(
                    eventStream = eventStream,
                    scope = liveStoreScope.coroutineScope,
                    nowMs = { testDispatcher.scheduler.currentTime },
                    wait = {
                        // Failed REST retries under canCatchUp would never idle the
                        // test scheduler (500ms, 1s, 2s… forever). Chat tests only
                        // need a single loader attempt.
                        throw kotlinx.coroutines.CancellationException("test-no-retry")
                    },
                ),
                nowMs = { testDispatcher.scheduler.currentTime },
                wait = { ms ->
                    if (ms < LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS) delay(ms)
                },
            )
        val activeConnectionManager =
            ActiveConnectionManager(connectionProfileStore, SideQuestionProbing.takeIf { sideQuestionsSupported })
        runBlocking {
            val profile =
                connectionProfileStore.activeProfile()
                    ?: ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "")
            activeConnectionManager.applyProfile(profile, serverProtocol = serverProtocol)
            if (sideQuestionsSupported) activeConnectionManager.probeActive()
        }
        val connectionSessionCoordinator =
            testConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = connectionProfileStore,
                secureCredentialStore = FakeSecureCredentialStore(),
                sessionModelSelectionStore = sessionModelSelectionStore,
                composerDraftStore = composerDraftStore,
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                eventStream = eventStream,
            )
        val catalogStore =
            modelCatalogStore
                ?: testModelCatalogStore(modelRepository, agentRepository, activeConnectionManager::currentProfile)
        return SessionChatViewModel(
            messageRepository = repository,
            modelCatalogStore = catalogStore,
            commandRepository = commandRepository,
            agentRepository = agentRepository,
            sessionRepository = sessionRepository,
            permissionLiveStore = permissionLiveStore,
            questionLiveStore = questionLiveStore,
            sessionReadyStore = sessionReadyStore,
            sessionStatusLiveStore = sessionStatusLiveStore,
            foregroundEventStream = eventStream,
            eventDomainAdapter = EventDomainAdapter(json),
            connectionSessionCoordinator = connectionSessionCoordinator,
            sessionModelSelectionStore = sessionModelSelectionStore,
            composerDraftStore = composerDraftStore,
            newSessionModelSelectionResolver =
                dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver(
                    store = sessionModelSelectionStore,
                    modelCatalogStore = catalogStore,
                ),
            connectionProfileStore = connectionProfileStore,
            activeConnectionManager = activeConnectionManager,
            liveUpdates = liveUpdates,
            sessionViewReporter = sessionViewReporter,
            projectFilesRepository = projectFilesRepository,
            homeProjectsSnapshot = homeProjectsSnapshot,
            worktreeRepository = worktreeRepository,
            worktreeInventoryCache = worktreeInventoryCache,
            sideQuestionStore =
                sideQuestionStore ?: SideQuestionStore(
                    request = { sessionId, question -> sessionRepository.askSideQuestion(sessionId, question) },
                    scope = liveStoreScope.coroutineScope,
                ),
            attachmentUploads = attachmentUploads,
        ).apply {
            ioDispatcher = testDispatcher
            nowMs = { testDispatcher.scheduler.currentTime }
            this.catalogRetryDelaysMs = catalogRetryDelaysMs
            // Mutations (send/rename/fork/new) require Live; default fakes start Connected.
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
        }
    }

    private fun testModelCatalogStore(
        modelRepository: ModelRepository,
        agentRepository: dev.opencode.mobile.domain.agent.AgentRepository = FakeAgentRepository(),
        currentProfile: () -> ConnectionProfile? = { ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode") },
    ): ModelCatalogStore =
        ModelCatalogStore(
            modelRepository = modelRepository,
            agentRepository = agentRepository,
            currentProfile = currentProfile,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
        )

    private fun longMarkdownHistory(): List<Message> =
        (1..20).map { messageIndex ->
            Message(
                id = "benchmark-$messageIndex",
                role = MessageRole.Assistant,
                text =
                    buildString {
                        append("## Response $messageIndex\n\n```typescript\n")
                        repeat(150) { lineIndex ->
                            append("const item$lineIndex = response$lineIndex?.value ?? 'markdown benchmark';\n")
                        }
                        append("```\n")
                    },
                sentAtEpochMs = messageIndex.toLong(),
            )
        }

    private suspend fun measureMs(block: suspend () -> Unit): Long {
        val startedAt = System.nanoTime()
        block()
        return (System.nanoTime() - startedAt) / 1_000_000
    }

    private fun reviewChangesMessage(id: String) =
        SessionChatMessageItem(
            id = id,
            role = MessageRole.User,
            text = "Change it",
            metadataLabel = "Build",
        )
}

private class RecordingSessionViewReporter : SessionViewReporter {
    val focusCalls = mutableListOf<String>()
    val releaseCalls = mutableListOf<String>()

    override fun focus(sessionId: String) {
        focusCalls.add(sessionId)
    }

    override fun release(sessionId: String) {
        releaseCalls.add(sessionId)
    }

    override fun clearCache() = Unit
}

private class RecordingSessionReadyStore : SessionReadyStore {
    val focusCalls = mutableListOf<Pair<String?, String?>>()
    val releaseCalls = mutableListOf<String>()

    override fun observeReadyIds(directory: String): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())

    override fun notifyPromptSent(
        sessionId: String,
        directory: String,
    ) = Unit

    override fun setFocusedSession(
        sessionId: String?,
        directory: String?,
    ) {
        focusCalls.add(sessionId to directory)
    }

    override fun releaseFocusedSession(sessionId: String) {
        releaseCalls.add(sessionId)
    }

    override fun bindProfile(profileId: String?) = Unit

    override fun clearCache() = Unit
}

private class FakeSessionModelSelectionStore(
    private val loadGate: CompletableDeferred<Unit>? = null,
) : SessionModelSelectionStore {
    private val saved = mutableMapOf<String, SessionModelSelection>()
    private val preferences = mutableMapOf<String, SessionModelSelection>()

    fun seed(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        saved[key(serverBaseUrl, worktree, sessionId)] = selection
    }

    suspend fun load(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? {
        loadGate?.await()
        return saved[key(serverBaseUrl, worktree, sessionId)]
    }

    suspend fun save(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        saved[key(serverBaseUrl, worktree, sessionId)] = selection
    }

    override suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = load(connectionIdentity.serverBaseUrl, worktree, sessionId)

    override suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) = save(connectionIdentity.serverBaseUrl, worktree, sessionId, selection)

    override suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) {
        saved.remove(key(connectionIdentity.serverBaseUrl, worktree, sessionId))
    }

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) {
        saved.keys.removeAll { it.startsWith(connectionIdentity.serverBaseUrl) && it.endsWith("|$sessionId") }
    }

    override suspend fun clearAll() {
        saved.clear()
        preferences.clear()
    }

    override suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = preference(connectionIdentity, worktree)

    override suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    ) {
        preferences["${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree"] = selection
    }

    fun preference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = preferences["${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree"]

    private fun key(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): String = "$serverBaseUrl|$worktree|$sessionId"
}

private const val HARNESS_BASE_URL = "http://127.0.0.1:4096/"

/** Every inventory load waits until [open], then answers [answer]. */
private class GatedWorktreeRepository(
    private val answer: WorktreeLoad,
) : WorktreeRepository {
    private val gate = CompletableDeferred<Unit>()
    var answered = 0
        private set

    fun open() {
        gate.complete(Unit)
    }

    private suspend fun gated(): WorktreeLoad {
        gate.await()
        answered += 1
        return answer
    }

    override suspend fun load(
        addedPath: String,
        projectId: String?,
        mainWorktree: String?,
    ): WorktreeLoad = gated()

    override suspend fun loadRefreshed(
        addedPath: String,
        projectId: String?,
        mainWorktree: String?,
    ): WorktreeLoad = gated()

    override suspend fun refresh(projectId: String): Boolean = false

    override suspend fun refetch(projectId: String): WorktreeLoad = gated()

    override fun onDisconnected() = Unit

    override fun onConnection(key: String) = Unit
}

private class FakeConnectionProfileStore(
    private var active: dev.opencode.mobile.domain.connection.ConnectionProfile? = null,
    var worktrees: MutableList<String> = mutableListOf(),
) : dev.opencode.mobile.domain.connection.ConnectionProfileStore {
    override suspend fun activeProfile(): dev.opencode.mobile.domain.connection.ConnectionProfile? = active

    override suspend fun saveProfile(profile: dev.opencode.mobile.domain.connection.ConnectionProfile) {
        active = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = worktrees.toList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        if (!worktrees.contains(worktree)) worktrees += worktree
    }

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        worktrees.remove(worktree)
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        this.worktrees = worktrees.toMutableList()
    }

    override suspend fun clearAll() {
        active = null
        worktrees.clear()
    }
}

private class FakeSecureCredentialStore : dev.opencode.mobile.domain.connection.SecureCredentialStore {
    override fun readPassword(serverBaseUrl: String): String? = null

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) = Unit

    override fun deletePassword(serverBaseUrl: String) = Unit

    override fun clearAll() = Unit
}

private val skillCatalog =
    listOf(
        ServerCommand(name = "init", source = ServerCommandSource.Command),
        ServerCommand(name = "tdd", description = "Test first", source = ServerCommandSource.Skill, skillId = "tdd"),
        ServerCommand(
            name = "security-check",
            description = "Security review",
            source = ServerCommandSource.Skill,
            skillId = "security-check",
        ),
    )

private class FakeCommandRepository(
    var commands: List<ServerCommand> =
        listOf(
            ServerCommand(name = "init", description = "Create AGENTS.md", source = ServerCommandSource.Command),
            ServerCommand(name = "my-skill", description = "A skill", source = ServerCommandSource.Skill),
        ),
    private val holdRunUntil: CompletableDeferred<Unit>? = null,
    private val runError: Throwable? = null,
) : CommandRepository {
    var runCommandCalled: Boolean = false
    var lastRunOptions: CommandRunOptions? = null
    var lastRunSessionId: String? = null

    val listCommandsDirectories = mutableListOf<String?>()

    override suspend fun listCommands(directory: String?): List<ServerCommand> {
        listCommandsDirectories += directory
        return commands
    }

    override suspend fun runCommand(
        sessionId: String,
        options: CommandRunOptions,
        directory: String?,
    ) {
        runCommandCalled = true
        lastRunSessionId = sessionId
        lastRunOptions = options
        holdRunUntil?.await()
        runError?.let { throw it }
    }
}

private class FakeAgentRepository(
    var agents: List<dev.opencode.mobile.domain.agent.MentionableAgent> =
        listOf(
            dev.opencode.mobile.domain.agent
                .MentionableAgent(name = "explore", description = "Read-only explorer"),
            dev.opencode.mobile.domain.agent
                .MentionableAgent(name = "general", description = "General helper"),
        ),
    var selectableAgents: List<dev.opencode.mobile.domain.agent.SelectableAgent> =
        dev.opencode.mobile.domain.agent.SelectableAgent.Fallback,
    private val selectableAgentsGate: CompletableDeferred<Unit>? = null,
) : dev.opencode.mobile.domain.agent.AgentRepository {
    override suspend fun listSelectableAgents(directory: String?): List<dev.opencode.mobile.domain.agent.SelectableAgent> {
        selectableAgentsGate?.await()
        return selectableAgents
    }

    override suspend fun listMentionableAgents(directory: String?): List<dev.opencode.mobile.domain.agent.MentionableAgent> = agents
}

private class FakeMessageRepository(
    var messages: List<Message> =
        listOf(
            Message(
                id = "msg-1",
                role = MessageRole.User,
                text = "Hello",
            ),
        ),
    var shouldFailLoad: Boolean = false,
    private val shouldFailSend: Boolean = false,
    private val failSendWithTransport: Boolean = false,
    var failListMessagesAfterSend: Boolean = false,
    private val echoSentPrompt: Boolean = false,
    private val holdSendUntil: CompletableDeferred<Unit>? = null,
    private val contentPreparer: MessageContentPreparer =
        MessageContentPreparer(OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default)).apply {
            computationDispatcher = Dispatchers.Unconfined
        },
) : MessageRepository {
    val eventStream = FakeForegroundEventStream()
    val catchUpChrome = MutableStateFlow(false)
    var sendPromptAsyncCalled: Boolean = false
    var sentOptions: PromptSendOptions? = null
    val shellRequests = mutableListOf<Pair<ShellCommandOptions, String?>>()
    var shellError: Throwable? = null
    private var listMessagesCallCount = 0

    override fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> =
        channelFlow {
            suspend fun loadUpdate(): MessageListUpdate =
                runCatching { listMessages(sessionId, directory) }
                    .fold(
                        onSuccess = { MessageListUpdate.Messages(it) },
                        onFailure = { MessageListUpdate.LoadFailed(it) },
                    )

            send(loadUpdate())

            eventStream.events
                .filter { event ->
                    event.type == "stream.reconnected" ||
                        (event.sessionId == sessionId && event.triggersMessageRefresh)
                }.debounce(300)
                .collect {
                    send(loadUpdate())
                }
        }

    override fun observeCatchUpChrome(): Flow<Boolean> = catchUpChrome

    override fun observeCatchUpChrome(
        sessionId: String,
        directory: String?,
    ): Flow<Boolean> = catchUpChrome

    val observedDisplayDirectories = mutableListOf<String?>()

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> =
        flow {
            observedDisplayDirectories += directory
            var previousById = emptyMap<String, DisplayMessage>()
            observeMessages(sessionId, directory).collect { update ->
                when (update) {
                    is MessageListUpdate.LoadFailed -> emit(DisplayMessageListUpdate.LoadFailed(update.error))
                    is MessageListUpdate.CacheSyncConfirmed ->
                        emit(DisplayMessageListUpdate.CacheSyncConfirmed(hasOlder = update.hasOlder))
                    is MessageListUpdate.Messages -> {
                        val prepared = contentPreparer.prepareMessages(update.messages, previousById)
                        previousById = prepared.associateBy { it.id }
                        emit(DisplayMessageListUpdate.Messages(prepared, isFromCache = update.isFromCache))
                    }
                }
            }
        }

    override suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = listMessages(sessionId, directory)

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> = contentPreparer.prepareMessages(refreshMessages(sessionId, directory))

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage = contentPreparer.prepareOptimisticUserMessage(id, text)

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
        attachments: List<Attachment>,
    ): DisplayMessage = contentPreparer.prepareOptimisticUserMessage(id, text, attachments)

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        listMessagesCallCount += 1
        if (shouldFailLoad) error("load failed")
        if (failListMessagesAfterSend && listMessagesCallCount > 1) error("reload failed")
        return messages
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) {
        sendPromptAsyncCalled = true
        sentOptions = options
        if (options.attachments.isNotEmpty()) {
            kotlin.coroutines.coroutineContext[PromptSendProgressReporter]
                ?.onProgress
                ?.invoke(1, 4)
        }
        holdSendUntil?.await()
        if (failSendWithTransport) throw MessageError.Transport(RuntimeException("offline"))
        if (shouldFailSend) error("send failed")
        if (echoSentPrompt && options.text.isNotEmpty()) {
            // Like the server, the echo keeps the client message id.
            messages = messages +
                Message(
                    id = options.messageId ?: "msg-sent",
                    role = MessageRole.User,
                    text = options.text,
                )
        }
    }

    override suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    ) {
        shellRequests += options to directory
        shellError?.let { throw it }
    }
}

private fun testAttachment(
    id: String = "attachment-1",
    filename: String = "photo.png",
    kind: AttachmentKind = AttachmentKind.Image,
    mime: String? = null,
): Attachment {
    val resolvedMime =
        mime
            ?: when (kind) {
                AttachmentKind.Text -> "text/plain"
                AttachmentKind.Pdf -> "application/pdf"
                AttachmentKind.Image -> "image/png"
                AttachmentKind.File -> "application/octet-stream"
            }
    return Attachment(
        id = id,
        filename = filename,
        mime = resolvedMime,
        kind = kind,
        dataUrl = "data:$resolvedMime;base64,AA==",
        byteCount = 1,
    )
}

private class FakeModelRepository(
    private val catalog: ModelSelectionCatalog? = null,
    /** Answers returned once each, in order, before falling back to [catalog]. */
    queued: List<ModelSelectionCatalog> = emptyList(),
) : ModelRepository {
    private val queued = ArrayDeque(queued)

    fun enqueue(catalog: ModelSelectionCatalog) {
        queued.addLast(catalog)
    }

    var calls = 0
        private set

    /** Per-directory answers, checked after [queued] and before [catalog]. */
    var catalogsByDirectory: Map<String, ModelSelectionCatalog> = emptyMap()

    /** While set, calls wait on it; lets a test observe state before any answer resolves. */
    var gate: CompletableDeferred<Unit>? = null

    /** While set, calls fail with it. */
    var error: Throwable? = null

    override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
        calls += 1
        gate?.await()
        error?.let { throw it }
        queued.removeFirstOrNull()?.let { return it }
        catalogsByDirectory[directory]?.let { return it }
        catalog?.let { return it }
        val option =
            ModelSelectionOption(
                providerId = "anthropic",
                providerName = "Anthropic",
                modelId = "claude-sonnet",
                modelName = "Claude Sonnet",
                variants = listOf("low", "high"),
                contextLimitTokens = 128_000,
            )
        return ModelSelectionCatalog(options = listOf(option), serverDefault = option)
    }
}

private class FakeSessionRepository(
    private val sessions: List<Session> = emptyList(),
    private val pendingPermissions: List<PermissionRequest> = emptyList(),
    private val pendingQuestions: List<QuestionRequest> = emptyList(),
    private val permissionResponseError: SessionError? = null,
    private val questionResponseError: SessionError? = null,
    private val listSessionsError: Throwable? = null,
    private val sessionDiffs: List<FileDiff> = emptyList(),
    private val sessionDiffsByMessageId: Map<String, List<FileDiff>> = emptyMap(),
    private val sessionDiffDelayMsByMessageId: Map<String, Long> = emptyMap(),
    private val sessionDiffError: SessionError? = null,
    private var sessionStatusType: String = "idle",
    private val abortErrorsById: Map<String, Throwable> = emptyMap(),
    private val sessionStatusError: SessionError? = null,
    private val updateSessionError: SessionError? = null,
    private val archiveError: Throwable? = null,
    private val deleteError: Throwable? = null,
    private val deleteResult: Boolean = true,
    private val archiveHoldUntil: CompletableDeferred<Unit>? = null,
    private val deleteHoldUntil: CompletableDeferred<Unit>? = null,
    private val questionHoldUntil: CompletableDeferred<Unit>? = null,
    var getSessionResult: Session? = null,
    var childSessions: List<Session> = emptyList(),
    private val getSessionError: Throwable? = null,
    private val createSessionResult: Session? = null,
    private val createSessionError: Throwable? = null,
    private val forkSessionResult: Session? = null,
    private val forkSessionError: Throwable? = null,
    private val revertError: Throwable? = null,
    private val unrevertError: Throwable? = null,
    private val commitRevertError: Throwable? = null,
    private val summarizeError: Throwable? = null,
    private val summarizeHoldUntil: CompletableDeferred<Unit>? = null,
    private val compactStart: CompactionStart = CompactionStart.Finished,
) : SessionRepository {
    val childSessionRequests = mutableListOf<String>()
    var childSessionFailuresRemaining = 0

    data class DiffRequest(
        val sessionId: String,
        val messageId: String?,
        val directory: String?,
    )

    data class UpdateRequest(
        val id: String,
        val title: String?,
        val directory: String?,
    )

    data class LifecycleRequest(
        val id: String,
        val directory: String?,
    )

    data class ForkRequest(
        val id: String,
        val atMessageId: String?,
        val directory: String?,
    )

    data class RevertRequest(
        val id: String,
        val messageId: String,
        val partId: String?,
        val directory: String?,
    )

    data class SummarizeRequest(
        val id: String,
        val providerId: String,
        val modelId: String,
        val directory: String?,
    )

    var pendingPermissionLoads: Int = 0
        private set
    var pendingQuestionLoads: Int = 0
        private set
    var respondedPermissionId: String? = null
        private set
    var respondedSessionId: String? = null
        private set
    var repliedQuestionId: String? = null
        private set
    var rejectedQuestionId: String? = null
        private set
    var diffRequest: DiffRequest? = null
        private set
    var updateRequest: UpdateRequest? = null
        private set
    var archiveRequest: LifecycleRequest? = null
        private set
    var archiveCallCount: Int = 0
        private set
    var deleteRequest: LifecycleRequest? = null
        private set
    var deleteCallCount: Int = 0
        private set
    var getSessionRequest: LifecycleRequest? = null
        private set
    var createSessionRequest: LifecycleRequest? = null
        private set
    var createSessionCallCount: Int = 0
        private set
    var forkSessionRequest: ForkRequest? = null
        private set
    var forkSessionCallCount: Int = 0
        private set
    var revertRequest: RevertRequest? = null
        private set
    var unrevertRequest: LifecycleRequest? = null
        private set
    val commitRevertRequests = mutableListOf<LifecycleRequest>()

    /** Runs when a revert commit arrives, before it succeeds or fails. */
    var onCommitRevert: (() -> Unit)? = null
    var summarizeRequest: SummarizeRequest? = null
        private set
    var abortCallCount: Int = 0
        private set
    var abortSessionIds: List<String> = emptyList()
        private set
    var listSessionsCallCount: Int = 0
        private set

    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> {
        pendingPermissionLoads += 1
        return pendingPermissions
    }

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> {
        pendingQuestionLoads += 1
        return pendingQuestions
    }

    override suspend fun listSessions(directory: String?): List<Session> {
        listSessionsCallCount += 1
        listSessionsError?.let { throw it }
        return sessions
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session {
        createSessionRequest = LifecycleRequest(id = parentId.orEmpty(), directory = directory)
        createSessionCallCount += 1
        createSessionError?.let { throw it }
        return createSessionResult ?: Session(id = "new-ses", title = title.orEmpty(), directory = directory)
    }

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> {
        sessionStatusError?.let { throw it }
        return listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to sessionStatusType)))
    }

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session {
        getSessionRequest = LifecycleRequest(id = id, directory = directory)
        getSessionError?.let { throw it }
        return getSessionResult ?: Session(id = id, title = "Title", directory = directory)
    }

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session {
        updateRequest = UpdateRequest(id, title, directory)
        updateSessionError?.let { throw it }
        return Session(id = id, title = title.orEmpty(), directory = directory)
    }

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session {
        archiveCallCount += 1
        archiveRequest = LifecycleRequest(id = id, directory = directory)
        archiveHoldUntil?.await()
        archiveError?.let { throw it }
        return Session(id = id, title = "Archived", archivedAtEpochMs = 1L)
    }

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean {
        deleteCallCount += 1
        deleteRequest = LifecycleRequest(id = id, directory = directory)
        deleteHoldUntil?.await()
        deleteError?.let { throw it }
        return deleteResult
    }

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> {
        childSessionRequests += id
        if (childSessionFailuresRemaining > 0) {
            childSessionFailuresRemaining -= 1
            throw SessionError.Http(statusCode = 503, message = "unavailable")
        }
        return childSessions
    }

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = error("unused")

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session {
        forkSessionCallCount += 1
        forkSessionRequest = ForkRequest(id = id, atMessageId = atMessageId, directory = directory)
        forkSessionError?.let { throw it }
        return forkSessionResult ?: Session(id = "forked-ses", title = "Untitled", directory = directory)
    }

    val moveSessionRequests = mutableListOf<Pair<String, String>>()
    val sideQuestionRequests = mutableListOf<Pair<String, String>>()

    /** One pending reply per `/btw` request, completed by the test. */
    val sideQuestionReplies = mutableListOf<CompletableDeferred<String>>()

    /** Branch per directory for `getVcsInfo`; a directory missing here answers 404. */
    var vcsBranches: Map<String, String> = emptyMap()
    val vcsInfoRequests = mutableListOf<String?>()

    override suspend fun getVcsInfo(directory: String?): dev.opencode.mobile.domain.session.VcsInfo {
        vcsInfoRequests += directory
        val branch = vcsBranches[directory] ?: throw SessionError.Http(404, "no vcs")
        return dev.opencode.mobile.domain.session.VcsInfo(currentBranch = branch, defaultBranch = "dev")
    }

    override suspend fun getVcsStatus(directory: String?): List<dev.opencode.mobile.domain.session.VcsFileStatus> = emptyList()

    override suspend fun moveSession(
        id: String,
        directory: String,
    ) {
        moveSessionRequests += id to directory
    }

    override suspend fun askSideQuestion(
        sessionId: String,
        question: String,
    ): String {
        sideQuestionRequests += sessionId to question
        val reply = CompletableDeferred<String>()
        sideQuestionReplies += reply
        return reply.await()
    }

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean {
        abortCallCount += 1
        abortSessionIds = abortSessionIds + id
        abortErrorsById[id]?.let { throw it }
        sessionStatusType = "idle"
        return true
    }

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> {
        diffRequest = DiffRequest(id, messageId, directory)
        sessionDiffDelayMsByMessageId[messageId]?.let { delay(it) }
        sessionDiffError?.let { throw it }
        return sessionDiffsByMessageId[messageId] ?: sessionDiffs
    }

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean {
        summarizeRequest = SummarizeRequest(id = id, providerId = providerId, modelId = modelId, directory = directory)
        summarizeHoldUntil?.await()
        summarizeError?.let { throw it }
        return true
    }

    override suspend fun compactSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): CompactionStart {
        summarizeSession(id, providerId, modelId, directory)
        return compactStart
    }

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): Boolean {
        revertRequest = RevertRequest(id = id, messageId = messageId, partId = partId, directory = directory)
        revertError?.let { throw it }
        getSessionResult =
            (getSessionResult ?: Session(id = id, title = "Title", directory = directory))
                .copy(revert = SessionRevert(messageId = messageId))
        return true
    }

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ): Boolean {
        unrevertRequest = LifecycleRequest(id = id, directory = directory)
        unrevertError?.let { throw it }
        getSessionResult = getSessionResult?.copy(revert = null)
        return true
    }

    override suspend fun commitRevert(
        id: String,
        directory: String?,
    ): Boolean {
        commitRevertRequests += LifecycleRequest(id = id, directory = directory)
        onCommitRevert?.invoke()
        commitRevertError?.let { throw it }
        getSessionResult = getSessionResult?.copy(revert = null)
        return true
    }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: dev.opencode.mobile.domain.session.PermissionDecision,
        directory: String?,
    ): Boolean {
        respondedSessionId = sessionId
        respondedPermissionId = permissionId
        permissionResponseError?.let { throw it }
        return true
    }

    override suspend fun replyToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean {
        repliedQuestionId = requestId
        questionHoldUntil?.await()
        questionResponseError?.let { throw it }
        return true
    }

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean {
        rejectedQuestionId = requestId
        questionHoldUntil?.await()
        questionResponseError?.let { throw it }
        return true
    }
}

private class FakePermissionLiveStore(
    initialRequests: List<PermissionRequest>,
) : PermissionLiveStore {
    val requests = MutableStateFlow(initialRequests)

    override fun observePermissions(directory: String): Flow<List<PermissionRequest>> = requests

    override fun clearCache() = Unit
}

private fun permissionRequest(
    id: String,
    sessionId: String,
) = PermissionRequest(
    id = id,
    sessionId = sessionId,
    permission = "bash",
    patterns = listOf("git status"),
    metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
    always = emptyList(),
    toolMessageId = null,
    toolCallId = null,
)

private class FakeQuestionLiveStore(
    initialRequests: List<QuestionRequest>,
) : QuestionLiveStore {
    val requests = MutableStateFlow(initialRequests)

    override fun observeQuestions(directory: String): Flow<List<QuestionRequest>> = requests

    override fun clearCache() = Unit
}

private fun questionRequest(
    id: String,
    sessionId: String,
) = QuestionRequest(
    id = id,
    sessionId = sessionId,
    questions =
        listOf(
            QuestionInfo(
                question = "Which approach?",
                header = "Approach",
                options = listOf(QuestionOption(label = "A", description = "First")),
            ),
        ),
    toolMessageId = null,
    toolCallId = null,
)

/** A v2 server whose contract declares `POST /api/session/{id}/generate`. */
private object SideQuestionProbing : ConnectionProbing {
    override suspend fun probe(connection: ServerConnection): ServerProtocol = ServerProtocol.V2

    override suspend fun probeV2Features(connection: ServerConnection): V2ServerFeatures = V2ServerFeatures(sessionGenerate = true)
}

private class FakeAttachmentUploads(
    private val handler: suspend (onProgress: (Long, Long) -> Unit) -> AttachmentUploadResult,
) : AttachmentUploadService {
    var calls = 0
        private set
    var contractMissed = false
        private set
    var cancelled = false
        private set

    override fun isAvailable(): Boolean = !contractMissed

    override suspend fun upload(
        file: java.io.File,
        filename: String,
        directory: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): AttachmentUploadResult {
        calls += 1
        return try {
            handler(onProgress)
        } catch (error: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw error
        }
    }

    override fun onContractMiss() {
        contractMissed = true
    }
}
