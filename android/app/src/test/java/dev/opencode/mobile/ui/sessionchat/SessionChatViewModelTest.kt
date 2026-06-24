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
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.ui.markdown.RenderedMarkdownMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
            assertEquals(TimelineState.Ready, state.timeline)
            assertNotNull(state.messages.single().renderedMarkdown)
        }

    @Test
    fun benchmark_loadMessages_longMarkdownHistory() =
        runTest(testDispatcher) {
            val messages = longMarkdownHistory()
            val highlighter = OpenCodeBundledJoniMarkdownHighlighter.default()
            val detectorMs = measureMs { messages.count { OpenCodeMarkdownDetector.shouldRender(it.text) } }
            val parserMs = measureMs {
                messages.forEach { message ->
                    if (OpenCodeMarkdownDetector.shouldRender(message.text)) {
                        OpenCodeMarkdownParser.parse(message.text, highlighter)
                    }
                }
            }
            val mapper = RenderedMarkdownMapper(highlighter).apply { computationDispatcher = Dispatchers.Default }
            val mapperMs = measureMs {
                mapper.enrichRows(
                    messages.map { message ->
                        SessionChatMessageItem(
                            id = message.id,
                            role = message.role,
                            text = message.text,
                            metadataLabel = "Benchmark",
                        )
                    },
                    previousById = emptyMap(),
                )
                Unit
            }
            val viewModel =
                createViewModel(
                    repository = FakeMessageRepository(messages = messages),
                    highlighter = highlighter,
                )

            val startedAt = System.nanoTime()
            viewModel.bind("ses-benchmark", "Markdown benchmark", "/repo")
            advanceUntilIdle()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals(TimelineState.Ready, viewModel.uiState.value.timeline)
            assertEquals(20, viewModel.uiState.value.messages.size)
            println(
                "MARKDOWN_BENCHMARK android detector-ms=$detectorMs parser-ms=$parserMs " +
                    "mapper-ms=$mapperMs viewmodel-load-ms=$elapsedMs messages=20 code-lines=3000",
            )
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
                                    inlines = listOf(dev.opencode.mobile.markdown.MarkdownInline.Text("Partial")),
                                ),
                            ),
                    ),
            )

        val item =
            viewModel.toMessageItem(
                Message(
                    id = "msg-1",
                    role = MessageRole.Assistant,
                    text = "# Partial heading",
                    parts = listOf(MessagePart.Text(text = "# Partial heading", isStreaming = true)),
                ),
                previous,
            )

        assertNotNull(item.renderedMarkdown)
    }

    private fun createViewModel(
        repository: FakeMessageRepository,
        markdownDispatcher: CoroutineDispatcher = testDispatcher,
        highlighter: OpenCodeCodeHighlighter = OpenCodeSyntaxHighlighter.default,
    ): SessionChatViewModel {
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
            renderedMarkdownMapper =
                RenderedMarkdownMapper(highlighter).apply {
                    computationDispatcher = markdownDispatcher
                },
        )
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
