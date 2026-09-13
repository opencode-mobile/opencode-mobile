package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2InboxUserPayloadDto
import dev.opencode.mobile.domain.agent.AgentMention
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
    fun toMessage_toolPartsLeaveTextEmpty() {
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
        assertEquals("", message.text)
        val tool = message.parts.single() as MessagePart.Tool
        assertEquals(ToolStatus.Pending, tool.status)
        assertEquals("glob", tool.toolName)
    }

    @Test
    fun toMessage_mapsV2ToolNameWhenToolFieldAbsent() {
        val raw =
            """
            {
              "info": { "id": "msg-2", "role": "assistant" },
              "parts": [
                { "type": "tool", "name": "websearch", "state": { "status": "completed" } }
              ]
            }
            """.trimIndent()

        val dto = json.decodeFromString<MessageDto>(raw)
        val message = adapter.toMessage(dto)
        val tool = message.parts.single() as MessagePart.Tool
        assertEquals("websearch", tool.toolName)
        assertEquals(ToolStatus.Completed, tool.status)
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
    fun toSendRequest_mapsTextAndAttachmentsToPromptParts() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "Describe these",
                    attachments =
                        listOf(
                            Attachment(
                                id = "attachment-1",
                                filename = "photo.png",
                                mime = "image/png",
                                kind = AttachmentKind.Image,
                                dataUrl = "data:image/png;base64,AA==",
                                byteCount = 1,
                            ),
                            Attachment(
                                id = "attachment-2",
                                filename = "notes.txt",
                                mime = "text/plain",
                                kind = AttachmentKind.Text,
                                dataUrl = "data:text/plain;base64,SGk=",
                                byteCount = 2,
                            ),
                        ),
                ),
            )

        assertEquals(listOf("text", "file", "file"), body.parts.map { it.type })
        assertEquals("Describe these", body.parts[0].text)
        assertEquals("image/png", body.parts[1].mime)
        assertEquals("data:image/png;base64,AA==", body.parts[1].url)
        assertEquals("photo.png", body.parts[1].filename)
    }

    @Test
    fun toSendRequest_allowsAttachmentOnlyPrompt() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "",
                    attachments =
                        listOf(
                            Attachment(
                                id = "attachment-1",
                                filename = "brief.pdf",
                                mime = "application/pdf",
                                kind = AttachmentKind.Pdf,
                                dataUrl = "data:application/pdf;base64,AA==",
                                byteCount = 1,
                            ),
                        ),
                ),
            )

        assertEquals(listOf("file"), body.parts.map { it.type })
        assertEquals("brief.pdf", body.parts.single().filename)
    }

    @Test
    fun toSendRequest_includesStructuredAgentPartsForMentions() {
        val body =
            adapter.toSendRequest(
                PromptSendOptions(
                    text = "@explore please",
                    agentMentions =
                        listOf(
                            AgentMention(id = "m1", name = "explore", start = 0, end = 8),
                        ),
                    mode = "build",
                ),
            )

        assertEquals(listOf("text", "agent"), body.parts.map { it.type })
        assertEquals("@explore please", body.parts[0].text)
        assertEquals("explore", body.parts[1].name)
        assertEquals("@explore", body.parts[1].source?.value)
        assertEquals(0, body.parts[1].source?.start)
        assertEquals(8, body.parts[1].source?.end)
        assertEquals("build", body.agent)
    }

    @Test
    fun toV2PromptRequest_mapsTextFilesAndAgents() {
        val body =
            adapter.toV2PromptRequest(
                PromptSendOptions(
                    text = "@explore please",
                    attachments =
                        listOf(
                            Attachment(
                                id = "attachment-1",
                                filename = "notes.txt",
                                mime = "text/plain",
                                kind = AttachmentKind.Text,
                                dataUrl = "data:text/plain;base64,SGk=",
                                byteCount = 2,
                            ),
                        ),
                    agentMentions =
                        listOf(
                            AgentMention(id = "m1", name = "explore", start = 0, end = 8),
                        ),
                    mode = "build",
                ),
            )

        assertEquals("@explore please", body.text)
        assertEquals("steer", body.delivery)
        assertEquals("data:text/plain;base64,SGk=", body.files?.single()?.uri)
        assertEquals("notes.txt", body.files?.single()?.name)
        assertEquals("text/plain", body.files?.single()?.mime)
        assertEquals("explore", body.agents?.single()?.name)
        assertEquals(
            "@explore",
            body.agents
                ?.single()
                ?.mention
                ?.text,
        )
    }

    @Test
    fun toV2SwitchModelRequest_mapsProviderModelAndVariant() {
        val body =
            adapter.toV2SwitchModelRequest(
                PromptSendOptions(
                    text = "Hello",
                    model = PromptModelSelection(providerId = "anthropic", modelId = "claude-sonnet"),
                    variant = "high",
                ),
            )

        assertEquals("claude-sonnet", body?.model?.id)
        assertEquals("anthropic", body?.model?.providerId)
        assertEquals("high", body?.model?.variant)
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
        assertFalse(part.isSynthetic)
        assertFalse(part.isIgnored)
        assertEquals("prt-1", part.id)
    }

    @Test
    fun toMessagePart_textType_mapsSyntheticAndIgnoredFlags() {
        val dto =
            MessagePartDto(
                id = "prt-1",
                type = "text",
                text = "file body",
                synthetic = true,
                ignored = true,
            )
        val part = adapter.toMessagePart(dto) as MessagePart.Text
        assertTrue(part.isSynthetic)
        assertTrue(part.isIgnored)
        assertFalse(part.isDisplayable)
    }

    @Test
    fun toMessagePart_reasoningType_returnsReasoningPart() {
        val dto = MessagePartDto(id = "prt-1", type = "reasoning", text = "thinking...")
        val part = adapter.toMessagePart(dto)
        assertTrue(part is MessagePart.Reasoning)
        assertEquals("thinking...", (part as MessagePart.Reasoning).text)
        assertEquals("prt-1", part.id)
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
        assertEquals("prt-1", tool.id)
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
        assertEquals(
            "opencode",
            tool.input
                ?.get("query")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("\u23F3 Searching...", adapter.compactToolLabel(tool))
    }

    @Test
    fun toMessagePart_runningMapsTimeStart() {
        val state =
            buildJsonObject {
                put("status", "running")
                put(
                    "time",
                    buildJsonObject {
                        put("start", 1_710_000_000_000)
                    },
                )
            }
        val dto = MessagePartDto(id = "prt-1", type = "tool", tool = "task", state = state)
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals(1_710_000_000_000, tool.timeStartEpochMs)
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
        val hiddenTypes = listOf("step-start", "step-finish", "agent", "subtask", "retry", "snapshot", "patch")
        for (type in hiddenTypes) {
            val dto = MessagePartDto(id = "prt-1", type = type)
            assertNull("$type should return null", adapter.toMessagePart(dto))
        }
    }

    @Test
    fun toMessagePart_compaction_returnsCompactionPart() {
        val dto = MessagePartDto(id = "prt-c", type = "compaction", auto = true)
        val part = adapter.toMessagePart(dto) as MessagePart.Compaction
        assertEquals("prt-c", part.id)
        assertTrue(part.auto)
    }

    @Test
    fun toMessagePart_textTypeMissingText_returnsNull() {
        val dto = MessagePartDto(id = "prt-1", type = "text")
        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_toolTypeMissingTool_returnsNamelessTool() {
        val dto = MessagePartDto(id = "prt-1", type = "tool")
        val tool = adapter.toMessagePart(dto) as MessagePart.Tool
        assertEquals("", tool.toolName)
        assertEquals("prt-1", tool.id)
    }

    @Test
    fun toMessagePart_dataUrlFile_returnsFilePart() {
        val dto =
            MessagePartDto(
                id = "prt-file",
                type = "file",
                mime = "image/png",
                url = "data:image/png;base64,AA==",
                filename = "photo.png",
            )

        val file = adapter.toMessagePart(dto) as MessagePart.File

        assertEquals("prt-file", file.id)
        assertEquals("photo.png", file.filename)
        assertEquals(AttachmentKind.Image, file.kind)
        assertEquals("data:image/png;base64,AA==", file.dataUrl)
    }

    @Test
    fun toMessagePart_dataUrlFileWithoutMime_infersMime() {
        val dto =
            MessagePartDto(
                id = "prt-file",
                type = "file",
                filename = "Photo 1.jpeg",
                url = "data:image/jpeg;base64,AA==",
            )

        val file = adapter.toMessagePart(dto) as MessagePart.File

        assertEquals("Photo 1.jpeg", file.filename)
        assertEquals("image/jpeg", file.mime)
        assertEquals(AttachmentKind.Image, file.kind)
        assertEquals("data:image/jpeg;base64,AA==", file.dataUrl)
    }

    @Test
    fun toMessagePart_nonDataUrlFile_returnsNull() {
        val dto = MessagePartDto(type = "file", mime = "text/plain", url = "file:///repo/readme.txt", filename = "readme.txt")

        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_fileWithoutUrl_keepsImagePlaceholder() {
        val dto =
            MessagePartDto(
                id = "prt-file",
                type = "file",
                mime = "image/jpeg",
                filename = "Photo 1.jpeg",
            )

        val file = adapter.toMessagePart(dto) as MessagePart.File

        assertEquals("prt-file", file.id)
        assertEquals("Photo 1.jpeg", file.filename)
        assertEquals("image/jpeg", file.mime)
        assertEquals(AttachmentKind.Image, file.kind)
        assertEquals("", file.dataUrl)
    }

    @Test
    fun toMessagePart_fileWithoutUrlOrMime_infersImageFromFilename() {
        val dto =
            MessagePartDto(
                id = "prt-file",
                type = "file",
                filename = "Photo 1.jpeg",
            )

        val file = adapter.toMessagePart(dto) as MessagePart.File

        assertEquals("image/jpeg", file.mime)
        assertEquals(AttachmentKind.Image, file.kind)
        assertEquals("", file.dataUrl)
    }

    @Test
    fun toMessagePart_dataUrlMimeHeaderMismatch_returnsNull() {
        val dto =
            MessagePartDto(
                type = "file",
                mime = "image/png",
                url = "data:image/jpeg;base64,AA==",
                filename = "photo.png",
            )

        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_malformedDataUrlHeader_returnsNull() {
        val dto =
            MessagePartDto(
                type = "file",
                mime = "image/png",
                url = "data:image/png,AA==",
                filename = "photo.png",
            )

        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_invalidBase64_returnsNull() {
        val dto =
            MessagePartDto(
                type = "file",
                mime = "text/plain",
                url = "data:text/plain;base64,not base64!",
                filename = "notes.txt",
            )

        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_unsupportedDataUrlKind_returnsNull() {
        val dto =
            MessagePartDto(
                type = "file",
                mime = "application/zip",
                url = "data:application/zip;base64,AA==",
                filename = "archive.zip",
            )

        assertNull(adapter.toMessagePart(dto))
    }

    @Test
    fun toMessagePart_missingId_usesDataUrlAsStableIdentity() {
        val dataUrl = "data:text/plain;base64,SGk="
        val dto = MessagePartDto(type = "file", mime = "text/plain", url = dataUrl, filename = "notes.txt")

        val file = adapter.toMessagePart(dto) as MessagePart.File

        assertEquals(dataUrl, file.id)
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
    fun flattenedText_textAndToolParts_isTextOnly() {
        val parts =
            listOf<MessagePart>(
                MessagePart.Text("Look at this"),
                MessagePart.Tool(toolName = "webfetch", status = ToolStatus.Completed),
            )
        assertEquals("Look at this", adapter.flattenedText(parts))
    }

    @Test
    fun flattenedText_emptyParts_returnsEmptyString() {
        assertEquals("", adapter.flattenedText(emptyList()))
    }

    @Test
    fun flattenedText_skipsSyntheticAndIgnoredTextParts() {
        val parts =
            listOf<MessagePart>(
                MessagePart.Text("User prompt", id = "prt-1"),
                MessagePart.Text(
                    "Called the Read tool with the following input: {\"filePath\":\"notes.txt\"}",
                    id = "prt-2",
                    isSynthetic = true,
                ),
                MessagePart.Text("file body contents", id = "prt-3", isSynthetic = true),
                MessagePart.Text("ignored", id = "prt-4", isIgnored = true),
            )
        assertEquals("User prompt", adapter.flattenedText(parts))
    }

    @Test
    fun toMessage_hidesSyntheticTextFileBodyFromDisplayText() {
        val dataUrl = "data:text/plain;base64,ZmlsZSBib2R5"
        val raw =
            """
            {
              "info": { "id": "msg-1", "role": "user", "sessionID": "ses-1" },
              "parts": [
                { "type": "text", "id": "prt-1", "text": "I attached logs" },
                {
                  "type": "text",
                  "id": "prt-2",
                  "text": "Called the Read tool with the following input: {\"filePath\":\"notes.txt\"}",
                  "synthetic": true
                },
                { "type": "text", "id": "prt-3", "text": "file body", "synthetic": true },
                {
                  "type": "file",
                  "id": "prt-4",
                  "mime": "text/plain",
                  "filename": "notes.txt",
                  "url": "$dataUrl"
                }
              ]
            }
            """.trimIndent()
        val dto = json.decodeFromString<MessageDto>(raw)
        val message = adapter.toMessage(dto)
        assertEquals("I attached logs", message.text)
        assertEquals(4, message.parts.size)
        assertTrue((message.parts[1] as MessagePart.Text).isSynthetic)
        assertTrue((message.parts[2] as MessagePart.Text).isSynthetic)
        assertTrue(message.parts[3] is MessagePart.File)
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
        assertEquals("Here is the answer.", message.text)
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

    @Test
    fun projectV2_mapsUserAssistantAndSkipsSwitches() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {"id":"msg_switch","type":"agent-switched","agent":"build","time":{"created":1}},
                  {"id":"msg_user","type":"user","text":"Hello","time":{"created":2}},
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "agent":"build",
                    "model":{"id":"gpt-5","providerID":"openai"},
                    "content":[
                      {"type":"text","id":"txt_1","text":"Hi there"},
                      {
                        "type":"tool",
                        "id":"tool_1",
                        "name":"bash",
                        "state":{
                          "status":"completed",
                          "input":{"command":"ls"},
                          "content":[{"type":"text","text":"src"}],
                          "structured":{}
                        }
                      }
                    ],
                    "cost":0.1,
                    "tokens":{"input":10,"output":4,"reasoning":0,"cache":{"read":0,"write":0}},
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val messages = adapter.projectV2(dtos)

        assertEquals(2, messages.size)
        assertEquals(MessageRole.User, messages[0].role)
        assertEquals("Hello", messages[0].text)
        assertEquals("build", messages[0].mode)
        assertEquals(MessageRole.Assistant, messages[1].role)
        assertEquals("Hi there", messages[1].text)
        assertEquals("gpt-5", messages[1].modelId)
        assertEquals("openai", messages[1].modelProviderId)
        val tool = messages[1].parts.filterIsInstance<MessagePart.Tool>().single()
        assertEquals("bash", tool.toolName)
        assertEquals(ToolStatus.Completed, tool.status)
        assertEquals("src", tool.output)
    }

    @Test
    fun projectV2_mapsReasoningContentIntoThoughtPart() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {"type":"reasoning","id":"r1","text":"Need to greet the user."},
                      {"type":"text","id":"t1","text":"Hello!"}
                    ],
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val parts = adapter.projectV2(dtos).single().parts
        val reasoning = parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertEquals("Need to greet the user.", reasoning.text)
        assertEquals("r1", reasoning.id)
        assertEquals("Hello!", parts.filterIsInstance<MessagePart.Text>().single().text)
    }

    @Test
    fun projectV2_thinkingAliasGetsReasoningPartAndId() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {"type":"thinking","text":"Need to greet the user."},
                      {"type":"text","text":"Hello!"}
                    ],
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val parts = adapter.projectV2(dtos).single().parts
        val reasoning = parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertEquals("Need to greet the user.", reasoning.text)
        assertEquals("msg_asst:thinking:0", reasoning.id)
        assertEquals("msg_asst:text:1", parts.filterIsInstance<MessagePart.Text>().single().id)
    }

    @Test
    fun projectV2_assistantInheritsCarriedAgentAndModel() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {"id":"msg_agent","type":"agent-switched","agent":"build","time":{"created":1}},
                  {"id":"msg_model","type":"model-switched","model":{"id":"gpt-5","providerID":"openai"},"time":{"created":2}},
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[{"type":"text","id":"t1","text":"Hi"}],
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val message = adapter.projectV2(dtos).single()

        assertEquals("build", message.mode)
        assertEquals("gpt-5", message.modelId)
        assertEquals("openai", message.modelProviderId)
    }

    @Test
    fun projectV2_countsAgentMentionTextForContextEstimate() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"",
                    "agents":[{"name":"review","mention":{"start":0,"end":17,"text":"agent prompt text"}}],
                    "time":{"created":1}
                  }
                ]
                """.trimIndent(),
            )

        val message = adapter.projectV2(dtos).single()

        assertEquals("agent prompt text".length, message.contextEstimate.userCharacters)
    }

    @Test
    fun projectV2_expandsShellAndCompaction() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_shell",
                    "type":"shell",
                    "callID":"call_1",
                    "command":"pwd",
                    "output":"/repo",
                    "time":{"created":1,"completed":2}
                  },
                  {
                    "id":"msg_compact",
                    "type":"compaction",
                    "reason":"auto",
                    "summary":"Earlier work summarized",
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val messages = adapter.projectV2(dtos)

        assertEquals(3, messages.size)
        assertEquals(MessageRole.User, messages[0].role)
        assertEquals("pwd", messages[0].text)
        assertEquals("msg_shell:assistant", messages[1].id)
        val shellTool = messages[1].parts.filterIsInstance<MessagePart.Tool>().single()
        assertEquals("bash", shellTool.toolName)
        assertEquals("/repo", shellTool.output)
        assertEquals("compaction", messages[2].mode)
        assertEquals("Earlier work summarized", messages[2].text)
        val compaction = messages[2].parts.filterIsInstance<MessagePart.Compaction>().single()
        assertTrue(compaction.auto)
        assertFalse(compaction.completed)
    }

    @Test
    fun projectV2_compactionStatusCompleted_marksPartComplete() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_compact",
                    "type":"compaction",
                    "reason":"manual",
                    "status":"completed",
                    "summary":"Done",
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val message = adapter.projectV2(dtos).single()
        val compaction = message.parts.filterIsInstance<MessagePart.Compaction>().single()
        assertTrue(compaction.completed)
        assertFalse(compaction.auto)
    }

    @Test
    fun projectV2_subagentMapsMetadataSessionIdAndOmitsToolNameTitle() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {
                        "type":"tool",
                        "id":"call_1",
                        "name":"subagent",
                        "state":{
                          "status":"completed",
                          "input":{"agent":"explore","description":"Find git projects"},
                          "metadata":{"sessionID":"ses_child","status":"completed"}
                        },
                        "time":{"created":10}
                      }
                    ],
                    "time":{"created":3}
                  }
                ]
                """.trimIndent(),
            )

        val tool =
            adapter
                .projectV2(dtos)
                .single()
                .parts
                .filterIsInstance<MessagePart.Tool>()
                .single()

        assertEquals("subagent", tool.toolName)
        assertEquals(ToolStatus.Completed, tool.status)
        assertNull(tool.title)
        assertEquals(
            "explore",
            tool.input
                ?.get("agent")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "ses_child",
            tool.metadata
                ?.get("sessionID")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(10L, tool.timeStartEpochMs)
    }

    @Test
    fun projectV2_userInlineFileDataBuildsDataUrl() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"see file",
                    "files":[{
                      "name":"notes.txt",
                      "mime":"text/plain",
                      "data":"SGk=",
                      "source":{"type":"inline"}
                    }]
                  }
                ]
                """.trimIndent(),
            )

        val file =
            adapter
                .projectV2(dtos)
                .single()
                .parts
                .filterIsInstance<MessagePart.File>()
                .single()
        assertEquals("notes.txt", file.filename)
        assertEquals("text/plain", file.mime)
        assertEquals("data:text/plain;base64,SGk=", file.dataUrl)
    }

    @Test
    fun projectV2_userFileWithoutUri_keepsImagePlaceholder() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"What",
                    "files":[{
                      "name":"Photo 1.jpeg",
                      "mime":"image/jpeg"
                    }]
                  }
                ]
                """.trimIndent(),
            )

        val file =
            adapter
                .projectV2(dtos)
                .single()
                .parts
                .filterIsInstance<MessagePart.File>()
                .single()
        assertEquals("Photo 1.jpeg", file.filename)
        assertEquals("image/jpeg", file.mime)
        assertEquals(AttachmentKind.Image, file.kind)
        assertEquals("", file.dataUrl)
    }

    @Test
    fun projectV2_shellOutputObjectDoesNotFailDecode() {
        val dtos =
            json.decodeFromString<List<dev.opencode.mobile.data.remote.dto.V2SessionMessageDto>>(
                """
                [
                  {
                    "id":"msg_shell",
                    "type":"shell",
                    "command":"pwd",
                    "output":{"output":"/repo","truncated":false},
                    "time":{"created":1,"completed":2}
                  }
                ]
                """.trimIndent(),
            )

        val tool =
            adapter
                .projectV2(dtos)[1]
                .parts
                .filterIsInstance<MessagePart.Tool>()
                .single()
        assertEquals("/repo", tool.output)
    }

    @Test
    fun mergeInbox_marksExistingUserMessageWaiting() {
        val messages =
            listOf(
                Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                Message(id = "msg-2", role = MessageRole.Assistant, text = "Hi"),
            )
        val merged =
            adapter.mergeInbox(
                messages,
                listOf(
                    V2InboxItemDto(
                        id = "msg-1",
                        timeCreated = 10,
                        type = "user",
                        payload = V2InboxUserPayloadDto("Hello"),
                        delivery = "steer",
                    ),
                ),
            )

        assertEquals(2, merged.size)
        assertTrue(merged[0].isWaitingForPickup)
        assertEquals(FollowUpDelivery.Steer, merged[0].followUpDelivery)
        assertFalse(merged[1].isWaitingForPickup)
    }

    @Test
    fun mergeInbox_materialisesMissingUserItem() {
        val messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello"))
        val merged =
            adapter.mergeInbox(
                messages,
                listOf(
                    V2InboxItemDto(
                        id = "inbox-2",
                        timeCreated = 20,
                        type = "user",
                        payload = V2InboxUserPayloadDto("Held prompt"),
                        delivery = "queue",
                    ),
                ),
            )

        assertEquals(2, merged.size)
        assertEquals("inbox-2", merged[1].id)
        assertEquals("Held prompt", merged[1].text)
        assertEquals(MessageRole.User, merged[1].role)
        assertTrue(merged[1].isWaitingForPickup)
        assertEquals(FollowUpDelivery.Queue, merged[1].followUpDelivery)
        assertEquals(20L, merged[1].sentAtEpochMs)
    }

    @Test
    fun mergeInbox_ignoresNonUserItems() {
        val messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello"))
        val merged =
            adapter.mergeInbox(
                messages,
                listOf(
                    V2InboxItemDto(id = "syn-1", type = "synthetic", timeCreated = 1),
                    V2InboxItemDto(id = "cmp-1", type = "compaction", timeCreated = 2),
                    V2InboxItemDto(id = "mov-1", type = "move", timeCreated = 3),
                ),
            )

        assertEquals(messages, merged)
    }

    @Test
    fun mergeInbox_emptyInboxIsNoOp() {
        val messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello"))
        assertEquals(messages, adapter.mergeInbox(messages, emptyList()))
    }
}
