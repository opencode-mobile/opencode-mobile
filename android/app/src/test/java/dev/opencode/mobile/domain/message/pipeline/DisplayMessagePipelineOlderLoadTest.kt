package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import dev.opencode.mobile.markdown.MarkdownDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayMessagePipelineOlderLoadTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun loadOlderDisplayMessages_preparesOnceAndSkipsWhenNoRowsAdded() =
        runTest(testDispatcher) {
            val preparer = CountingPreparer()
            val sync =
                MessageSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("c"), user("d")),
                            olderCursor = "page-1",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, cursor ->
                        when (cursor) {
                            "page-1" ->
                                MessagePage(
                                    messages = listOf(user("c"), user("d")),
                                    olderCursor = "page-2",
                                    hasOlder = true,
                                )
                            else ->
                                MessagePage(
                                    messages = listOf(user("a"), user("b")),
                                    olderCursor = null,
                                    hasOlder = false,
                                )
                        }
                    },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = FakeForegroundEventStream(),
                    liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = FakeForegroundEventStream(),
                            scope = CoroutineScope(SupervisorJob() + testDispatcher),
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { kotlinx.coroutines.delay(it) },
                        ),
                    nowMs = { testDispatcher.scheduler.currentTime },
                )
            val pipeline =
                DisplayMessagePipeline(
                    snapshotSource = sync.asDisplayMessageSnapshotSource(),
                    contentPreparer = preparer,
                    messageSync = sync,
                )

            sync.observeMessages("ses-1", "/repo").first()
            advanceUntilIdle()

            val overlap = pipeline.loadOlderDisplayMessages("ses-1", "/repo")
            assertEquals(0, overlap.addedCount)
            assertEquals(0, preparer.prepareCount)

            val added = pipeline.loadOlderDisplayMessages("ses-1", "/repo")
            assertEquals(2, added.addedCount)
            assertEquals(listOf("a", "b", "c", "d"), added.messages.map { it.id })
            assertEquals(1, preparer.prepareCount)
            assertEquals(MessagePreparationMode.Final, preparer.lastMode)
        }

    private fun user(id: String) = Message(id = id, role = MessageRole.User, text = id)

    private class CountingPreparer : DisplayMessagePreparing {
        var prepareCount = 0
        var lastMode: MessagePreparationMode? = null

        override suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage>,
            mode: MessagePreparationMode,
        ): List<DisplayMessage> {
            prepareCount += 1
            lastMode = mode
            return messages.map(::DisplayMessage)
        }

        override suspend fun prepareMessage(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): DisplayMessage = DisplayMessage(message)

        override suspend fun prepareTextBlock(
            blockID: String,
            plain: String,
            isStreaming: Boolean,
            sessionKey: String,
            mode: MessagePreparationMode,
        ): MarkdownDocument? = null

        override suspend fun prepareStructureOnlyTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): MarkdownDocument? = null
    }
}
