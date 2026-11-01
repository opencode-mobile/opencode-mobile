package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.TimelineNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineChangeNoticesTest {
    private fun user(
        id: String,
        agent: String?,
        model: String?,
        variant: String? = null,
        at: Long,
    ) = DisplayMessage(
        message =
            Message(
                id = id,
                role = MessageRole.User,
                text = id,
                parts = listOf(MessagePart.Text(id = "$id:text", text = id)),
                model = model,
                modelProviderId = "opencode",
                modelId = model,
                variant = variant,
                mode = agent,
                sentAtEpochMs = at,
            ),
    )

    private fun assistant(
        id: String,
        at: Long,
    ) = DisplayMessage(Message(id = id, role = MessageRole.Assistant, text = "ok", model = "m", mode = "build", sentAtEpochMs = at))

    private fun kinds(messages: List<DisplayMessage>): List<String> =
        messages.map { display ->
            when (val notice = (display.message.parts.firstOrNull() as? MessagePart.Notice)?.notice) {
                null -> display.message.id
                is TimelineNotice.AgentChanged -> "agent:${notice.previous ?: "-"}>${notice.agent}"
                is TimelineNotice.ModelChanged ->
                    "model:${notice.previous?.let { "${it.modelId}/${it.variant ?: "-"}" } ?: "-"}>" +
                        "${notice.model.modelId}/${notice.model.variant ?: "-"}"
                else -> "notice"
            }
        }

    @Test
    fun derivesEachChangeButNotTheFirstAgentAndModel() {
        val result =
            TimelineChangeNotices.withDerivedNotices(
                listOf(
                    user("u1", "build", "fast", at = 1),
                    assistant("a1", 2),
                    user("u2", "build", "fast", at = 3),
                    assistant("a2", 4),
                    user("u3", "plan", "fast", at = 5),
                    user("u4", "plan", "smart", at = 6),
                    user("u5", "plan", "smart", variant = "high", at = 7),
                ),
            )
        assertEquals(
            listOf(
                "u1",
                "a1",
                "u2",
                "a2",
                "agent:build>plan",
                "u3",
                "model:fast/->smart/-",
                "u4",
                "model:smart/->smart/high",
                "u5",
            ),
            kinds(result),
        )
    }

    @Test
    fun derivedNoticesSitBeforeTheirMessageWithItsTime() {
        val result =
            TimelineChangeNotices.withDerivedNotices(listOf(user("u1", "build", null, at = 1), user("u2", "plan", null, at = 42)))
        assertEquals(3, result.size)
        assertEquals(MessageRole.Notice, result[1].message.role)
        assertEquals(42L, result[1].message.sentAtEpochMs)
        assertTrue(result[1].message.id.startsWith(TimelineChangeNotices.DERIVED_ID_PREFIX))
    }

    @Test
    fun serverNoticesOwnTheSession() {
        val serverNotice =
            DisplayMessage(
                Message(
                    id = "n1",
                    role = MessageRole.Notice,
                    text = "",
                    parts = listOf(MessagePart.Notice(id = "n1", notice = TimelineNotice.AgentChanged("build"))),
                    sentAtEpochMs = 1,
                ),
            )
        val input = listOf(serverNotice, user("u1", "build", "fast", at = 2), user("u2", "plan", "fast", at = 3))
        assertEquals(listOf("agent:->build", "u1", "u2"), kinds(TimelineChangeNotices.withDerivedNotices(input)))
    }

    @Test
    fun messagesWithoutAgentOrModelDeriveNothing() {
        val result = TimelineChangeNotices.withDerivedNotices(listOf(user("u1", null, null, at = 1), assistant("a1", 2)))
        assertEquals(listOf("u1", "a1"), kinds(result))
    }
}
