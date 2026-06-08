package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.MessageLiveStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
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
    fun loadMessages_success() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository())
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(TimelineState.Ready, state.timeline)
            assertEquals(1, state.messages.size)
            assertEquals("Hello", state.messages.first().text)
        }

    @Test
    fun loadMessages_empty() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository(messages = emptyList()))
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(TimelineState.Empty, viewModel.uiState.value.timeline)
        }

    @Test
    fun loadMessages_error() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeMessageRepository(shouldFailLoad = true))
            viewModel.bind("ses-1", "Title", "/repo")
            advanceUntilIdle()

            assertEquals(TimelineState.Error, viewModel.uiState.value.timeline)
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
            assertEquals(
                "Hello",
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
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
                viewModel.uiState.value.selectedModel
                    ?.option
                    ?.providerId,
            )
            assertEquals(
                "claude-sonnet",
                viewModel.uiState.value.selectedModel
                    ?.option
                    ?.modelId,
            )
            assertEquals("high", viewModel.uiState.value.selectedVariant)
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
            val item = viewModel.toMessageItem(message)
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
            val item = viewModel.toMessageItem(message)
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
                    text = "\u2713 bash",
                    parts = listOf(MessagePart.Tool(toolName = "bash", statusText = "\u2713 bash")),
                )
            val item = viewModel.toMessageItem(message)
            assertFalse(item.isStreaming)
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

    private fun createViewModel(repository: FakeMessageRepository): SessionChatViewModel {
        val eventStream = repository.eventStream
        val sessionRepository = FakeSessionRepository()
        val liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        val sessionStatusLiveStore = DefaultSessionStatusLiveStore(sessionRepository, eventStream, liveStoreScope)
        val messageLiveStore = EventDrivenFakeMessageLiveStore(repository)
        return SessionChatViewModel(
            messageRepository = repository,
            modelRepository = FakeModelRepository(),
            sessionRepository = sessionRepository,
            messageLiveStore = messageLiveStore,
            sessionStatusLiveStore = sessionStatusLiveStore,
            foregroundEventStream = eventStream,
            eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
        )
    }
}

@OptIn(FlowPreview::class)
private class EventDrivenFakeMessageLiveStore(
    private val repository: FakeMessageRepository,
) : MessageLiveStore {
    override fun clearCache() = Unit

    override fun observeMessages(
        sessionId: String,
        directory: String,
    ): Flow<List<Message>> =
        channelFlow {
            suspend fun loadMessages(): List<Message>? = runCatching { repository.listMessages(sessionId, directory) }.getOrNull()

            loadMessages()?.let { send(it) }

            repository.eventStream.events
                .filter { event ->
                    event.type == "stream.reconnected" ||
                        (event.sessionId == sessionId && event.triggersMessageRefresh)
                }.debounce(300)
                .collect {
                    loadMessages()?.let { send(it) }
                }
        }
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
    private val shouldFailLoad: Boolean = false,
    private val shouldFailSend: Boolean = false,
    var failListMessagesAfterSend: Boolean = false,
    private val holdSendUntil: CompletableDeferred<Unit>? = null,
) : MessageRepository {
    val eventStream = FakeForegroundEventStream()
    var sendPromptAsyncCalled: Boolean = false
    var sentOptions: PromptSendOptions? = null
    private var listMessagesCallCount = 0

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
        if (shouldFailSend) error("send failed")
        holdSendUntil?.await()
    }
}

private class FakeModelRepository : ModelRepository {
    override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
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

private class FakeSessionRepository : SessionRepository {
    override suspend fun listSessions(directory: String?) = error("unused")

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?) =
        listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "idle")))

    override suspend fun getSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ) = error("unused")

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
    ) = error("unused")

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
    ) = error("unused")
}
