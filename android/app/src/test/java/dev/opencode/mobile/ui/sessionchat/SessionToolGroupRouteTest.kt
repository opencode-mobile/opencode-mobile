package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.SessionToolGroup
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.ShellCommandOptions
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetails
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolGroupTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionToolGroupMembers
import dev.opencode.mobile.ui.sessionchat.view.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1033: the tool group route key carries ids only; the route resolves its members from the transcript. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionToolGroupRouteTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun routeKey_staysTheSameSizeHoweverLargeTheToolOutput() {
        val small = route(message(outputChars = 1_000))
        val large = route(message(outputChars = 5_000_000))

        assertEquals(small.toString().length, large.toString().length)
        assertTrue(large.toString().length < 2_000)
        val json = Json
        val smallSaved = json.encodeToString(SessionToolGroup.serializer(), small)
        val largeSaved = json.encodeToString(SessionToolGroup.serializer(), large)
        assertEquals(smallSaved.length, largeSaved.length)
        assertEquals(large, json.decodeFromString(SessionToolGroup.serializer(), largeSaved))
    }

    @Test
    fun members_resolveToTheCardsTheTimelineDrew() {
        val message = message(outputChars = 5_000_000)
        val group = group(message)

        assertEquals(group.members, SessionToolGroupMembers.resolve(route(message), listOf(message)))
    }

    @Test
    fun members_goneWhenTheMessageOrEveryMemberIsGone() {
        val message = message(outputChars = 10)
        val route = route(message)

        assertNull(SessionToolGroupMembers.resolve(route, emptyList()))
        assertNull(SessionToolGroupMembers.resolve(route, listOf(message.copy(parts = emptyList()))))
        // A member that is gone is left out; the rest still show.
        val partial = message.copy(parts = message.parts.dropLast(1))
        assertEquals(2, SessionToolGroupMembers.resolve(route, listOf(partial))!!.size)
    }

    @Test
    fun viewModel_readsTheStore_buildsDetailsOnOpen_andGoesBackWhenTheGroupLeaves() {
        val message = message(outputChars = 300_000)
        val repository = FakeTranscriptRepository()
        val viewModel = SessionToolGroupViewModel(repository)
        val route = route(message)

        viewModel.bind(route)
        assertEquals(SessionToolGroupMembersState.Loading, viewModel.members.value)
        assertEquals("/repo-transcript", repository.observedDirectory)

        // A cached frame without the group waits for the live load.
        repository.updates.tryEmit(MessageListUpdate.Messages(emptyList(), isFromCache = true))
        assertEquals(SessionToolGroupMembersState.Loading, viewModel.members.value)

        repository.updates.tryEmit(MessageListUpdate.Messages(listOf(message)))
        val members = (viewModel.members.value as SessionToolGroupMembersState.Ready).members
        assertEquals(group(message).members, members)
        val detail = viewModel.toolDetail(members.first())!!
        assertEquals(
            message.parts
                .filterIsInstance<MessagePart.Tool>()
                .first()
                .output,
            detail.body,
        )
        assertEquals(SessionChatToolDetails.target(members.first(), listOf(message)), detail)

        repository.updates.tryEmit(MessageListUpdate.Messages(emptyList()))
        assertEquals(SessionToolGroupMembersState.Gone, viewModel.members.value)
        assertNull(viewModel.toolDetail(members.first()))
    }

    private fun route(message: Message): SessionToolGroup {
        val group = group(message)
        return SessionChatToolGroupTarget(
            blockID = group.blockID,
            title = group.title,
            subtitle = group.subtitle,
            members = group.members,
            messageId = message.id,
        ).toRoute(
            sessionId = "ses-1",
            worktree = "/repo",
            transcriptWorktree = "/repo-transcript",
            parentId = null,
            owningTurnMessageId = message.id,
            supportsSessionMessageDiff = true,
        )
    }

    private fun group(message: Message): SessionChatTimelineBlock.ToolGroup {
        val result = SessionChatTimelineBlocks.build(MessageRole.Assistant, message.parts, collapseConsecutiveToolCalls = true)
        return (result as SessionChatTimelineBlocksResult.Ok).blocks.filterIsInstance<SessionChatTimelineBlock.ToolGroup>().single()
    }

    private fun message(outputChars: Int): Message =
        Message(
            id = "msg-1",
            role = MessageRole.Assistant,
            text = "",
            parts =
                (1..3).map { index ->
                    MessagePart.Tool(
                        toolName = "bash",
                        status = ToolStatus.Completed,
                        id = "prt-$index",
                        input = buildJsonObject { put("command", JsonPrimitive("cat log$index")) },
                        output = "x".repeat(outputChars),
                    )
                },
        )
}

private class FakeTranscriptRepository : MessageRepository {
    val updates = MutableSharedFlow<MessageListUpdate>(extraBufferCapacity = 8)
    var observedDirectory: String? = null

    override fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> {
        observedDirectory = directory
        return updates
    }

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> = error("unused")

    override suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = emptyList()

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> = emptyList()

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage = error("unused")

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = emptyList()

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) = Unit

    override suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    ) = Unit
}
