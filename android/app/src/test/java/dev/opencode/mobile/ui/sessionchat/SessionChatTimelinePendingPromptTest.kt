package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.OlderLoadResult
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatTimelinePendingPromptTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun concurrentDeleteThenQueue_composesBothMutations() =
        runTest(testDispatcher) {
            val repository = FakePendingPromptMessageRepository(listOf(steer("a"), steer("b")))
            val timeline = boundTimeline(repository)

            timeline.applyOptimisticPendingPrompt("a", removed = true)
            timeline.applyOptimisticPendingPrompt("b", FollowUpDelivery.Queue)
            advanceUntilIdle()

            assertEquals(listOf("b"), timeline.loadedMessages().map { it.id })
            assertQueued(timeline.loadedMessages().single())
        }

    @Test
    fun staleLiveSnapshot_doesNotRestoreDeletedPrompt() =
        runTest(testDispatcher) {
            val repository = FakePendingPromptMessageRepository(listOf(steer("a"), steer("b")))
            val timeline = boundTimeline(repository)

            timeline.applyOptimisticPendingPrompt("a", removed = true)
            advanceUntilIdle()
            repository.emit(listOf(steer("a"), steer("b")))
            advanceUntilIdle()

            assertEquals(listOf("b"), timeline.loadedMessages().map { it.id })
            assertFalse(timeline.loadedMessages().any { it.id == "a" })
        }

    @Test
    fun staleLiveSnapshot_keepsOptimisticQueueUntilAuthoritativeMatches() =
        runTest(testDispatcher) {
            val repository = FakePendingPromptMessageRepository(listOf(steer("a")))
            val timeline = boundTimeline(repository)

            timeline.applyOptimisticPendingPrompt("a", FollowUpDelivery.Queue)
            advanceUntilIdle()
            repository.emit(listOf(steer("a")))
            advanceUntilIdle()

            val queued = timeline.loadedMessages().single { it.id == "a" }
            assertQueued(queued)

            repository.emit(listOf(queued("a")))
            advanceUntilIdle()
            assertQueued(timeline.loadedMessages().single { it.id == "a" })
        }

    @Test
    fun authoritativeOmit_expiresRemovedPatch() =
        runTest(testDispatcher) {
            val repository = FakePendingPromptMessageRepository(listOf(steer("a"), steer("b")))
            val timeline = boundTimeline(repository)

            timeline.applyOptimisticPendingPrompt("a", removed = true)
            advanceUntilIdle()
            assertTrue(timeline.pendingPromptPatchCount() > 0)
            repository.emit(listOf(steer("b")))
            advanceUntilIdle()
            assertEquals(0, timeline.pendingPromptPatchCount())
            repository.emit(listOf(steer("a"), steer("b")))
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), timeline.loadedMessages().map { it.id })
        }

    @Test
    fun newerLiveSnapshot_isNotDroppedByOptimisticDelete() =
        runTest(testDispatcher) {
            val repository = FakePendingPromptMessageRepository(listOf(steer("a"), steer("b")))
            val timeline = boundTimeline(repository)

            timeline.applyOptimisticPendingPrompt("a", removed = true)
            repository.emit(listOf(steer("a"), steer("b"), steer("c")))
            advanceUntilIdle()

            assertEquals(listOf("b", "c"), timeline.loadedMessages().map { it.id })
        }

    private fun TestScope.boundTimeline(repository: FakePendingPromptMessageRepository): SessionChatTimeline {
        val dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
        return SessionChatTimeline(repository, CoroutineScope(dispatcher)).apply {
            ioDispatcher = dispatcher
            bind("ses-1", "/repo")
            advanceUntilIdle()
            assertTrue(loadedMessages().isNotEmpty())
        }
    }
}

private fun assertQueued(message: Message) {
    assertTrue(message.isWaitingForPickup)
    assertEquals(FollowUpDelivery.Queue, message.followUpDelivery)
}

private class FakePendingPromptMessageRepository(
    initial: List<Message>,
) : MessageRepository {
    private val live =
        MutableSharedFlow<DisplayMessageListUpdate>(replay = 1, extraBufferCapacity = 16).apply {
            tryEmit(DisplayMessageListUpdate.Messages(initial.map(::DisplayMessage)))
        }

    fun emit(messages: List<Message>) {
        live.tryEmit(DisplayMessageListUpdate.Messages(messages.map(::DisplayMessage)))
    }

    override fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> = emptyFlow()

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> = live

    override suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = error("unused")

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> = error("unused")

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage = DisplayMessage(steer(id))

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = error("unused")

    override suspend fun loadOlderMessages(
        sessionId: String,
        directory: String?,
    ): OlderLoadResult = OlderLoadResult(messages = emptyList(), hasOlder = false, addedCount = 0)

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) = Unit
}

private fun steer(id: String) =
    Message(
        id = id,
        role = MessageRole.User,
        text = id,
        followUpDelivery = FollowUpDelivery.Steer,
        isWaitingForPickup = true,
    )

private fun queued(id: String) =
    Message(
        id = id,
        role = MessageRole.User,
        text = id,
        followUpDelivery = FollowUpDelivery.Queue,
        isWaitingForPickup = true,
    )
