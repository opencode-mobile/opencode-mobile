package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val tool = message.parts.single() as MessagePart.Tool
        assertEquals(ToolStatus.Pending, tool.status)
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

    @Test
    fun toSendRequest_mapsModeToAgent() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "Hello",
                    model = PromptModelSelection(providerId = "anthropic", modelId = "claude-sonnet"),
                    mode = "plan",
                ),
            )

        assertEquals("plan", body.agent)
    }

    @Test
    fun toSendRequest_omitsModeWhenNull() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "Hello",
                    model = PromptModelSelection(providerId = "anthropic", modelId = "claude-sonnet"),
                ),
            )

        assertEquals(null, body.agent)
    }

    @Test
    fun compactToolLabel_pending_returnsHourglass() {
        assertEquals(
            "\u231B webfetch",
            adapter.compactToolLabel(MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Pending)),
        )
    }

    @Test
    fun compactToolLabel_runningWithoutTitle_returnsHourglassWithName() {
        assertEquals(
            "\u23F3 webfetch",
            adapter.compactToolLabel(MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Running)),
        )
    }

    @Test
    fun compactToolLabel_runningWithTitle_returnsHourglassWithTitle() {
        assertEquals(
            "\u23F3 Searching...",
            adapter.compactToolLabel(
                MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Running, title = "Searching..."),
            ),
        )
    }

    @Test
    fun compactToolLabel_completed_returnsCheckmark() {
        assertEquals(
            "\u2713 webfetch",
            adapter.compactToolLabel(MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Completed)),
        )
    }

    @Test
    fun compactToolLabel_errorWithoutMessage_returnsCrossWithDefault() {
        assertEquals(
            "\u2715 webfetch: failed",
            adapter.compactToolLabel(MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Error)),
        )
    }

    @Test
    fun compactToolLabel_errorWithMessage_returnsCrossWithMessage() {
        assertEquals(
            "\u2715 webfetch: timeout",
            adapter.compactToolLabel(
                MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Error, error = "timeout"),
            ),
        )
    }

    @Test
    fun toToolStatus_missingOrUnrecognized_mapsToPending() {
        assertEquals(ToolStatus.Pending, adapter.toToolStatus(null))
        assertEquals(ToolStatus.Pending, adapter.toToolStatus("unknown"))
        assertEquals(ToolStatus.Pending, adapter.toToolStatus(""))
    }

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
    fun toMessagePart_toolType_returnsStructuredToolPart() {
        val state = buildJsonObject { put("status", "completed") }
        val dto =
            MessagePartDto(
                id = "prt-1",
                type = "tool",
                tool = "webfetch",
                callId = "call-1",
                state = state,
            )
        val part = adapter.toMessagePart(dto)
        assertTrue(part is MessagePart.Tool)
        val tool = part as MessagePart.Tool
        assertEquals("webfetch", tool.toolName)
        assertEquals(ToolStatus.Completed, tool.status)
        assertEquals("call-1", tool.callId)
        assertNull(tool.title)
        assertNull(tool.error)
        assertEquals("\u2713 webfetch", adapter.compactToolLabel(tool))
    }

    @Test
    fun toMessagePart_missingToolState_mapsPending() {
        val dto = MessagePartDto(id = "prt-1", type = "tool", tool = "glob")
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals(ToolStatus.Pending, tool.status)
        assertEquals("\u231B glob", adapter.compactToolLabel(tool))
    }

    @Test
    fun toMessagePart_unknownStatus_mapsPending() {
        val state = buildJsonObject { put("status", "unknown") }
        val dto = MessagePartDto(id = "prt-1", type = "tool", tool = "webfetch", state = state)
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals(ToolStatus.Pending, tool.status)
        assertEquals("\u231B webfetch", adapter.compactToolLabel(tool))
    }

    @Test
    fun toMessagePart_runningWithTitleAndInput() {
        val state =
            buildJsonObject {
                put("status", "running")
                put("title", "Searching...")
                put("input", buildJsonObject { put("query", "opencode") })
            }
        val dto = MessagePartDto(id = "prt-1", type = "tool", tool = "websearch", state = state)
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals(ToolStatus.Running, tool.status)
        assertEquals("Searching...", tool.title)
        assertEquals("opencode", tool.input?.get("query")?.jsonPrimitive?.content)
        assertEquals("\u23F3 Searching...", adapter.compactToolLabel(tool))
    }

    @Test
    fun toMessagePart_errorPreservesErrorText() {
        val state =
            buildJsonObject {
                put("status", "error")
                put("error", "timeout")
            }
        val dto = MessagePartDto(id = "prt-1", type = "tool", tool = "webfetch", state = state)
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals(ToolStatus.Error, tool.status)
        assertEquals("timeout", tool.error)
        assertEquals("\u2715 webfetch: timeout", adapter.compactToolLabel(tool))
    }

    @Test
    fun toMessagePart_completedEdit_preservesMetadataWhileCompactLabelStaysSimple() {
        val state =
            buildJsonObject {
                put("status", "completed")
                put("title", "src/Main.kt")
                put("output", "edited")
                put(
                    "metadata",
                    buildJsonObject {
                        put(
                            "filediff",
                            buildJsonObject {
                                put("file", "src/Main.kt")
                                put("additions", 3)
                                put("deletions", 1)
                            },
                        )
                    },
                )
            }
        val dto =
            MessagePartDto(
                id = "prt-1",
                type = "tool",
                tool = "edit",
                callId = "call-edit-1",
                state = state,
            )
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals("edit", tool.toolName)
        assertEquals(ToolStatus.Completed, tool.status)
        assertEquals("src/Main.kt", tool.title)
        assertEquals("edited", tool.output)
        assertEquals("call-edit-1", tool.callId)
        val filediff = tool.metadata?.get("filediff")?.jsonObject
        assertEquals("src/Main.kt", filediff?.get("file")?.jsonPrimitive?.content)
        assertEquals(3, filediff?.get("additions")?.jsonPrimitive?.int)
        assertEquals(1, filediff?.get("deletions")?.jsonPrimitive?.int)
        assertEquals("\u2713 edit", adapter.compactToolLabel(tool))
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
                MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Completed),
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
        val tool = message.parts[1] as MessagePart.Tool
        assertEquals(ToolStatus.Completed, tool.status)
        assertEquals("bash", tool.toolName)
    }

    @Test
    fun toMessage_countsFilePartSourceTextForContextEstimate() {
        val dto =
            json.decodeFromString<MessageDto>(
                """
                {
                  "info": {"id": "msg-file", "role": "user"},
                  "parts": [
                    {
                      "id": "prt-file",
                      "sessionID": "ses-1",
                      "messageID": "msg-file",
                      "type": "file",
                      "mime": "text/plain",
                      "url": "file:///repo/file.txt",
                      "source": {
                        "type": "file",
                        "path": "/repo/file.txt",
                        "text": {"value": "file source text", "start": 0, "end": 16}
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val message = adapter.toMessage(dto)

        assertEquals("file source text".length, message.contextEstimate.userCharacters)
    }

    @Test
    fun toMessage_countsAgentPartSourceTextForContextEstimate() {
        val dto =
            json.decodeFromString<MessageDto>(
                """
                {
                  "info": {"id": "msg-agent", "role": "user"},
                  "parts": [
                    {
                      "id": "prt-agent",
                      "sessionID": "ses-1",
                      "messageID": "msg-agent",
                      "type": "agent",
                      "name": "review",
                      "source": {"value": "agent prompt text", "start": 0, "end": 17}
                    }
                  ]
                }
                """.trimIndent(),
            )

        val message = adapter.toMessage(dto)

        assertEquals("agent prompt text".length, message.contextEstimate.userCharacters)
    }
}
