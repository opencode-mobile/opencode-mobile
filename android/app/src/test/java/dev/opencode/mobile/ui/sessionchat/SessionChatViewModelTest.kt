package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.command.CommandRunOptions
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.command.ServerCommandSource
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.sessionchat.region.SessionChatReviewChanges
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
            assertEquals("Couldn't archive session.", viewModel.uiState.value.errorMessage)
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
            assertEquals("Couldn't delete session.", viewModel.uiState.value.errorMessage)
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
            assertEquals("Couldn't delete session.", viewModel.uiState.value.errorMessage)
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
            assertEquals("Unable to refresh permission session tree", viewModel.uiState.value.errorMessage)
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
        }

    @Test
    fun completedLatestTurnLoadsReviewChangesSummaryWithWorktree() =
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

            assertEquals(ReviewChangesSummary(2, 4, 2), viewModel.uiState.value.reviewChangesSummary)
            assertEquals(FakeSessionRepository.DiffRequest("ses-1", "user-1", "/repo"), sessionRepository.diffRequest)
        }

    @Test
    fun newerLatestTurnCancelsStaleReviewChangesSummary() =
        runTest(testDispatcher) {
            val staleDiff = FileDiff("stale.kt", null, 99, 99, FileDiffStatus.Modified)
            val latestDiff = FileDiff("latest.kt", null, 5, 3, FileDiffStatus.Modified)
            val repository =
                FakeSessionRepository(
                    sessionDiffsByMessageId = mapOf("user-1" to listOf(staleDiff), "user-2" to listOf(latestDiff)),
                    sessionDiffDelayMsByMessageId = mapOf("user-1" to 1_000L),
                )
            val region = SessionChatReviewChanges(repository, backgroundScope).apply { ioDispatcher = testDispatcher }
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
            assertEquals(ReviewChangesSummary(1, 5, 3), region.state.value.summary)
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
            assertEquals(FakeSessionRepository.DiffRequest("ses-1", "user-1", "/repo"), sessionRepository.diffRequest)
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
            val preparer = MessageContentPreparer(highlighter).apply { computationDispatcher = Dispatchers.Default }
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
            assertTrue(viewModel.uiState.value.slashSuggestions.any { it.commandName == "init" })
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
            assertTrue(viewModel.uiState.value.atSuggestions.any { it.agentName == "explore" })

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
            assertEquals("explore", repository.sentOptions?.agentMentions?.single()?.name)
            assertEquals("", viewModel.uiState.value.composer.text)
            assertTrue(viewModel.uiState.value.composer.agentMentions.isEmpty())
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
                "Hello",
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
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
            assertEquals(listOf(attachment), viewModel.uiState.value.composer.attachments)
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
            assertEquals(listOf("img", "txt"), viewModel.uiState.value.composer.attachments.map { it.id })
            assertEquals(
                "This model does not support PDF attachments.",
                viewModel.uiState.value.errorMessage,
            )

            viewModel.selectModel(textOnly)
            runCurrent()
            assertFalse(viewModel.uiState.value.composer.canAttach)
            assertEquals(listOf("txt"), viewModel.uiState.value.composer.attachments.map { it.id })
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
            assertEquals("send failed", viewModel.uiState.value.errorMessage)
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
                    .any { it.id.startsWith("pending-") },
            )
            sendGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun sendPrompt_planMode_sendsPlanAgent() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val viewModel = createViewModel(repository)
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            viewModel.selectMode("Plan")
            viewModel.updateComposerText("Plan me")
            viewModel.sendPrompt()
            advanceUntilIdle()

            assertEquals("plan", repository.sentOptions?.mode)
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
            assertEquals("send failed", viewModel.uiState.value.errorMessage)
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

            assertEquals("Image exceeds the size limit", viewModel.uiState.value.errorMessage)
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
                    Message(id = "msg-1", role = MessageRole.User, text = "New prompt"),
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
            assertEquals(SessionConnectionStatus.Live, EventStreamConnectionState.Connected.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.Connecting, EventStreamConnectionState.Connecting.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.Connecting, EventStreamConnectionState.Reconnecting.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.Offline, EventStreamConnectionState.Idle.toSessionConnectionStatus())
            assertEquals(SessionConnectionStatus.Offline, EventStreamConnectionState.Disconnected.toSessionConnectionStatus())
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
                    parts = listOf(MessagePart.Text(text = "Hello", isStreaming = true)),
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
                    parts = listOf(MessagePart.Text(text = "Done", isStreaming = false)),
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
                    parts = listOf(MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed)),
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
                    parts = listOf(firstFile, MessagePart.Text("Between"), secondFile),
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
                        parts = listOf(MessagePart.Text(text = "# Partial heading", isStreaming = true)),
                    ),
                    previous,
                ),
            )

        assertNotNull(item.renderedMarkdown)
    }

    private fun createViewModel(
        repository: FakeMessageRepository,
        modelRepository: FakeModelRepository = FakeModelRepository(),
        commandRepository: FakeCommandRepository = FakeCommandRepository(),
        agentRepository: FakeAgentRepository = FakeAgentRepository(),
        sessionModelSelectionStore: FakeSessionModelSelectionStore = FakeSessionModelSelectionStore(),
        sessionRepository: FakeSessionRepository = FakeSessionRepository(),
        permissionLiveStore: PermissionLiveStore = FakePermissionLiveStore(emptyList()),
    ): SessionChatViewModel {
        val eventStream = repository.eventStream
        val liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        val sessionStatusLiveStore = DefaultSessionStatusLiveStore(sessionRepository, eventStream, liveStoreScope)
        val json = Json { ignoreUnknownKeys = true }
        val connectionProfileStore = FakeConnectionProfileStore()
        val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
        runBlocking {
            activeConnectionManager.applyProfile(
                ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            )
        }
        val connectionSessionCoordinator =
            ConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = connectionProfileStore,
                secureCredentialStore = FakeSecureCredentialStore(),
                sessionModelSelectionStore = sessionModelSelectionStore,
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
            )
        return SessionChatViewModel(
            messageRepository = repository,
            modelRepository = modelRepository,
            commandRepository = commandRepository,
            agentRepository = agentRepository,
            sessionRepository = sessionRepository,
            permissionLiveStore = permissionLiveStore,
            sessionStatusLiveStore = sessionStatusLiveStore,
            foregroundEventStream = eventStream,
            eventDomainAdapter = EventDomainAdapter(json),
            connectionSessionCoordinator = connectionSessionCoordinator,
            sessionModelSelectionStore = sessionModelSelectionStore,
        ).apply { ioDispatcher = testDispatcher }
    }

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

private class FakeConnectionProfileStore : dev.opencode.mobile.domain.connection.ConnectionProfileStore {
    override suspend fun activeProfile(): dev.opencode.mobile.domain.connection.ConnectionProfile? = null

    override suspend fun saveProfile(profile: dev.opencode.mobile.domain.connection.ConnectionProfile) = Unit

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() = Unit
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

private class FakeCommandRepository(
    var commands: List<ServerCommand> =
        listOf(
            ServerCommand(name = "init", description = "Create AGENTS.md", source = ServerCommandSource.Command),
            ServerCommand(name = "my-skill", description = "A skill", source = ServerCommandSource.Skill),
        ),
) : CommandRepository {
    var runCommandCalled: Boolean = false
    var lastRunOptions: CommandRunOptions? = null
    var lastRunSessionId: String? = null

    override suspend fun listCommands(directory: String?): List<ServerCommand> = commands

    override suspend fun runCommand(
        sessionId: String,
        options: CommandRunOptions,
        directory: String?,
    ) {
        runCommandCalled = true
        lastRunSessionId = sessionId
        lastRunOptions = options
    }
}

private class FakeAgentRepository(
    var agents: List<dev.opencode.mobile.domain.agent.MentionableAgent> =
        listOf(
            dev.opencode.mobile.domain.agent.MentionableAgent(name = "explore", description = "Read-only explorer"),
            dev.opencode.mobile.domain.agent.MentionableAgent(name = "general", description = "General helper"),
        ),
) : dev.opencode.mobile.domain.agent.AgentRepository {
    override suspend fun listMentionableAgents(directory: String?): List<dev.opencode.mobile.domain.agent.MentionableAgent> =
        agents
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
    var failListMessagesAfterSend: Boolean = false,
    private val holdSendUntil: CompletableDeferred<Unit>? = null,
    private val contentPreparer: MessageContentPreparer =
        MessageContentPreparer(OpenCodeSyntaxHighlighter.default).apply {
            computationDispatcher = Dispatchers.Unconfined
        },
) : MessageRepository {
    val eventStream = FakeForegroundEventStream()
    var sendPromptAsyncCalled: Boolean = false
    var sentOptions: PromptSendOptions? = null
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

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> =
        flow {
            var previousById = emptyMap<String, DisplayMessage>()
            observeMessages(sessionId, directory).collect { update ->
                when (update) {
                    is MessageListUpdate.LoadFailed -> emit(DisplayMessageListUpdate.LoadFailed(update.error))
                    is MessageListUpdate.Messages -> {
                        val prepared = contentPreparer.prepareMessages(update.messages, previousById)
                        previousById = prepared.associateBy { it.id }
                        emit(DisplayMessageListUpdate.Messages(prepared))
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

    override suspend fun getMessage(
        sessionId: String,
        messageId: String,
        directory: String?,
    ): Message = error("unused")

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        directory: String?,
    ): Message = error("unused")

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) {
        sendPromptAsyncCalled = true
        sentOptions = options
        holdSendUntil?.await()
        if (shouldFailSend) error("send failed")
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
) : ModelRepository {
    override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
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
    private val permissionResponseError: SessionError? = null,
    private val listSessionsError: Throwable? = null,
    private val sessionDiffs: List<FileDiff> = emptyList(),
    private val sessionDiffsByMessageId: Map<String, List<FileDiff>> = emptyMap(),
    private val sessionDiffDelayMsByMessageId: Map<String, Long> = emptyMap(),
    private val sessionDiffError: SessionError? = null,
    private val sessionStatusType: String = "idle",
    private val sessionStatusError: SessionError? = null,
    private val updateSessionError: SessionError? = null,
    private val archiveError: Throwable? = null,
    private val deleteError: Throwable? = null,
    private val deleteResult: Boolean = true,
    private val archiveHoldUntil: CompletableDeferred<Unit>? = null,
    private val deleteHoldUntil: CompletableDeferred<Unit>? = null,
) : SessionRepository {
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

    var pendingPermissionLoads: Int = 0
        private set
    var respondedPermissionId: String? = null
        private set
    var respondedSessionId: String? = null
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

    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> {
        pendingPermissionLoads += 1
        return pendingPermissions
    }

    override suspend fun listSessions(directory: String?): List<Session> {
        listSessionsError?.let { throw it }
        return sessions
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> {
        sessionStatusError?.let { throw it }
        return listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to sessionStatusType)))
    }

    override suspend fun getSession(
        id: String,
        directory: String?,
    ) = error("unused")

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
    ) = error("unused")

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
    ) = error("unused")

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ) = true

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
    ) = error("unused")

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ) = error("unused")

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
}

private class FakePermissionLiveStore(
    initialRequests: List<PermissionRequest>,
) : PermissionLiveStore {
    private val requests = MutableStateFlow(initialRequests)

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
