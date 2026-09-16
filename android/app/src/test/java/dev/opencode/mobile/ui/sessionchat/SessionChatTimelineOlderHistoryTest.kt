package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatTimelineOlderHistoryTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun loadOlder_failureKeepsRowsAndAllowsRetry() =
        runTest(testDispatcher) {
            val repository = FakePagedMessageRepository()
            val dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
            val timeline =
                SessionChatTimeline(repository, CoroutineScope(dispatcher)).apply {
                    ioDispatcher = dispatcher
                }
            timeline.bind("ses-1", "/repo")
            advanceUntilIdle()

            assertEquals(
                listOf("c", "d"),
                timeline.state.value.messages
                    .map { it.id },
            )
            assertTrue(timeline.state.value.hasOlder)

            repository.failOlder = true
            timeline.loadOlder()
            advanceUntilIdle()

            assertEquals(
                listOf("c", "d"),
                timeline.state.value.messages
                    .map { it.id },
            )
            assertTrue(timeline.state.value.hasOlder)
            assertFalse(timeline.state.value.isLoadingOlder)

            repository.failOlder = false
            timeline.loadOlder()
            advanceUntilIdle()

            assertEquals(
                listOf("a", "b", "c", "d"),
                timeline.state.value.messages
                    .map { it.id },
            )
            assertFalse(timeline.state.value.hasOlder)
            assertEquals(2, repository.olderCalls)
        }
}

private class FakePagedMessageRepository : MessageRepository {
    var failOlder = false
    var olderCalls = 0
    private val live =
        MutableSharedFlow<DisplayMessageListUpdate>(replay = 1).apply {
            tryEmit(
                DisplayMessageListUpdate.Messages(
                    messages =
                        listOf(user("c"), user("d")).map(::DisplayMessage),
                    hasOlder = true,
                ),
            )
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
    ): List<Message> = listOf(user("c"), user("d"))

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> = refreshMessages(sessionId, directory).map(::DisplayMessage)

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage = DisplayMessage(user(id))

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = listOf(user("c"), user("d"))

    override suspend fun loadOlderMessages(
        sessionId: String,
        directory: String?,
    ): OlderLoadResult {
        olderCalls += 1
        if (failOlder) error("older failed")
        return OlderLoadResult(
            messages = listOf(user("a"), user("b"), user("c"), user("d")),
            hasOlder = false,
            addedCount = 2,
        )
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
    ) = Unit
}

private fun user(id: String) = Message(id = id, role = MessageRole.User, text = id)
