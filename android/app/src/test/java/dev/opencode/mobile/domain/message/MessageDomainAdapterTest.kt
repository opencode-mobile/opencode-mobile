package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.MessageToolStateDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDomainAdapterTest {
    private val adapter = MessageDomainAdapter()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun toMessage_mapsUserTextParts() {
        val dto =
            MessageDto(
                info =
                    MessageInfoDto(
                        id = "msg-1",
                        sessionId = "ses-1",
                        role = "user",
                        agent = "build",
                        model = null,
                        time = null,
                    ),
                parts = listOf(MessagePartDto(type = "text", text = "Hello")),
            )

        val message = adapter.toMessage(dto)

        assertEquals(MessageRole.User, message.role)
        assertEquals("Hello", message.text)
        assertEquals("build", message.mode)
    }

    @Test
    fun toMessage_toolPartsProduceFallbackText() {
        val raw =
            """
            {
              "info": { "id": "msg-2", "role": "assistant" },
              "parts": [
                { "type": "tool", "tool": "glob" }
              ]
            }
            """.trimIndent()

        val dto = json.decodeFromString<MessageDto>(raw)
        val message = adapter.toMessage(dto)

        assertEquals(MessageRole.Assistant, message.role)
        assertEquals("\u231B glob", message.text)
    }

    @Test
    fun toMessage_mapsAssistantErrorWhenPartsEmpty() {
        val raw =
            """
            {
              "info": {
                "id": "msg-3",
                "role": "assistant",
                "error": {
                  "name": "APIError",
                  "data": {
                    "message": "Forbidden: unauthorized: not licensed to use Copilot",
                    "statusCode": 403
                  }
                }
              },
              "parts": []
            }
            """.trimIndent()

        val dto = json.decodeFromString<MessageDto>(raw)
        val message = adapter.toMessage(dto)

        assertEquals(MessageRole.Assistant, message.role)
        assertEquals("Forbidden: unauthorized: not licensed to use Copilot", message.text)
    }

    @Test
    fun toSendRequest_includesModelAndNonDefaultVariant() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "Hello",
                    model = PromptModelSelection(providerId = "anthropic", modelId = "claude-sonnet"),
                    variant = "high",
                ),
            )

        assertEquals(listOf("Hello"), body.parts.map { it.text })
        assertEquals("anthropic", body.model?.providerId)
        assertEquals("claude-sonnet", body.model?.modelId)
        assertEquals("high", body.variant)
    }

    @Test
    fun toSendRequest_omitsDefaultVariant() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "Hello",
                    model = PromptModelSelection(providerId = "anthropic", modelId = "claude-sonnet"),
                    variant = null,
                ),
            )

        assertEquals("anthropic", body.model?.providerId)
        assertEquals(null, body.variant)
    }

    // MARK: - Tool status labels

    @Test
    fun toolStatusLabel_nilState_returnsHourglass() {
        assertEquals("\u231B webfetch", adapter.toolStatusLabel("webfetch", null))
    }

    @Test
    fun toolStatusLabel_pending_returnsHourglass() {
        val state = buildJsonObject { put("status", "pending") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("\u231B webfetch", adapter.toolStatusLabel("webfetch", parsed))
    }

    @Test
    fun toolStatusLabel_runningWithoutTitle_returnsHourglassWithName() {
        val state = buildJsonObject { put("status", "running") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("\u23F3 webfetch", adapter.toolStatusLabel("webfetch", parsed))
    }

    @Test
    fun toolStatusLabel_runningWithTitle_returnsHourglassWithTitle() {
        val state = buildJsonObject { put("status", "running"); put("title", "Searching...") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("\u23F3 Searching...", adapter.toolStatusLabel("webfetch", parsed))
    }

    @Test
    fun toolStatusLabel_completed_returnsCheckmark() {
        val state = buildJsonObject { put("status", "completed") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("\u2713 webfetch", adapter.toolStatusLabel("webfetch", parsed))
    }

    @Test
    fun toolStatusLabel_errorWithoutMessage_returnsCrossWithDefault() {
        val state = buildJsonObject { put("status", "error") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("\u2715 webfetch: failed", adapter.toolStatusLabel("webfetch", parsed))
    }

    @Test
    fun toolStatusLabel_errorWithMessage_returnsCrossWithMessage() {
        val state = buildJsonObject { put("status", "error"); put("error", "timeout") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("\u2715 webfetch: timeout", adapter.toolStatusLabel("webfetch", parsed))
    }

    @Test
    fun toolStatusLabel_unknownStatus_returnsPlainName() {
        val state = buildJsonObject { put("status", "unknown") }
        val parsed = json.decodeFromJsonElement<MessageToolStateDto>(state)
        assertEquals("webfetch", adapter.toolStatusLabel("webfetch", parsed))
    }

    // MARK: - MessagePart parsing

    @Test
    fun toMessagePart_textType_returnsTextPart() {
        val dto = MessagePartDto(id = "prt-1", type = "text", text = "Hello")
        val part = adapter.toMessagePart(dto)
        assertTrue(part is MessagePart.Text)
        assertEquals("Hello", (part as MessagePart.Text).text)
        assertFalse(part.isStreaming)
    }

    @Test
    fun toMessagePart_reasoningType_returnsNull() {
        val dto = MessagePartDto(id = "prt-1", type = "reasoning", text = "thinking...")
        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_toolType_returnsToolPart() {
        val state = buildJsonObject { put("status", "completed") }
        val dto = MessagePartDto(id = "prt-1", type = "tool", tool = "webfetch", state = state)
        val part = adapter.toMessagePart(dto)
        assertTrue(part is MessagePart.Tool)
        assertEquals("webfetch", (part as MessagePart.Tool).toolName)
        assertEquals("\u2713 webfetch", part.statusText)
    }

    @Test
    fun toMessagePart_hiddenTypes_returnNull() {
        val hiddenTypes = listOf("reasoning", "step-start", "step-finish", "agent", "subtask", "compaction", "retry", "snapshot", "patch")
        for (type in hiddenTypes) {
            val dto = MessagePartDto(id = "prt-1", type = type)
            assertNull("$type should return null", adapter.toMessagePart(dto))
        }
    }

    @Test
    fun toMessagePart_textTypeMissingText_returnsNull() {
        val dto = MessagePartDto(id = "prt-1", type = "text")
        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_toolTypeMissingTool_returnsNull() {
        val dto = MessagePartDto(id = "prt-1", type = "tool")
        assertNull(adapter.toMessagePart(dto))
    }

    // MARK: - Flattened text

    @Test
    fun flattenedText_singleTextPart() {
        val parts = listOf<MessagePart>(MessagePart.Text("Hello"))
        assertEquals("Hello", adapter.flattenedText(parts))
    }

    @Test
    fun flattenedText_multipleTextPartsJoinedByNewline() {
        val parts = listOf<MessagePart>(MessagePart.Text("First"), MessagePart.Text("Second"))
        assertEquals("First\nSecond", adapter.flattenedText(parts))
    }

    @Test
    fun flattenedText_textAndToolParts() {
        val parts =
            listOf<MessagePart>(
                MessagePart.Text("Look at this"),
                MessagePart.Tool("webfetch", "\u2713 webfetch"),
            )
        assertEquals("Look at this\n\u2713 webfetch", adapter.flattenedText(parts))
    }

    @Test
    fun flattenedText_emptyParts_returnsEmptyString() {
        assertEquals("", adapter.flattenedText(emptyList()))
    }

    @Test
    fun toMessage_parsesPartsIntoFlattenedText() {
        val raw =
            """
            {
              "info": { "id": "msg-1", "role": "assistant", "sessionID": "ses-1" },
              "parts": [
                { "type": "text", "id": "prt-1", "text": "Here is the answer." },
                { "type": "tool", "id": "prt-2", "tool": "bash", "state": { "status": "completed" } }
              ]
            }
            """.trimIndent()
        val dto = json.decodeFromString<MessageDto>(raw)
        val message = adapter.toMessage(dto)
        assertTrue(message.text.contains("Here is the answer."))
        assertTrue(message.text.contains("\u2713 bash"))
        assertEquals(2, message.parts.size)
    }
}
