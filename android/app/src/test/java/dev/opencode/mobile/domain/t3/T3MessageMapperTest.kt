package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T3MessageMapperTest {
    @Test
    fun threadSnapshot_mapsRolesAndText() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "messages": [
                      {
                        "id": "m-user",
                        "role": "user",
                        "text": "Hello",
                        "streaming": false,
                        "createdAt": "2026-10-03T12:00:00Z"
                      },
                      {
                        "id": "m-assistant",
                        "role": "assistant",
                        "text": "Hi there",
                        "streaming": true
                      },
                      {
                        "id": "m-system",
                        "role": "system",
                        "text": "System note"
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val messages = T3MessageMapper.toMessages(snapshot)
        assertEquals(3, messages.size)
        assertEquals(MessageRole.User, messages[0].role)
        assertEquals("Hello", messages[0].text)
        assertEquals(MessageRole.Assistant, messages[1].role)
        assertEquals("Hi there", messages[1].text)
        assertTrue(messages[1].parts.any { it is dev.opencode.mobile.domain.message.MessagePart.Text && it.isStreaming })
        assertEquals(MessageRole.Assistant, messages[2].role)
        assertEquals("System note", messages[2].text)
    }

    @Test
    fun assistantMarkdown_preparesBoldAndInlineCode() =
        runBlocking {
            val message =
                T3MessageMapper.toMessage(
                    T3ConversationMessage(
                        id = "m-protocol",
                        role = "assistant",
                        text = "Server **0.0.40** at `http://example:3773`.",
                        streaming = false,
                        createdAt = null,
                    ),
                )
            val display =
                MessageContentPreparer(
                    OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default),
                ).prepareMessage(message)
            val paragraph = display.renderedMarkdown?.blocks?.single() as MarkdownBlock.Paragraph
            assertTrue(paragraph.inlines.any { it is MarkdownInline.Strong })
            assertTrue(
                paragraph.inlines.any { it is MarkdownInline.Code && it.code == "http://example:3773" },
            )
        }

    @Test
    fun conversationMessage_mapsAttachmentsToFileParts() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "messages": [
                      {
                        "id": "m-user",
                        "role": "user",
                        "text": "Connected.",
                        "attachments": [
                          {
                            "type": "image",
                            "id": "att-image",
                            "name": "image.png",
                            "mimeType": "image/png",
                            "sizeBytes": 229320
                          },
                          {
                            "type": "file",
                            "id": "att-pdf",
                            "name": "notes.pdf",
                            "mimeType": "application/pdf",
                            "sizeBytes": 1200
                          },
                          {
                            "type": "clip",
                            "id": "att-unknown",
                            "name": "blob.bin",
                            "mimeType": "application/octet-stream",
                            "sizeBytes": 4
                          }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        assertEquals(229320L, snapshot.messages.single().attachments.first().sizeBytes)
        val message = T3MessageMapper.toMessages(snapshot).single()
        val files = message.parts.filterIsInstance<MessagePart.File>()
        assertEquals(listOf("Connected."), message.parts.filterIsInstance<MessagePart.Text>().map { it.text })
        assertEquals(3, files.size)
        assertEquals("att-image", files[0].id)
        assertEquals("image.png", files[0].filename)
        assertEquals(AttachmentKind.Image, files[0].kind)
        assertEquals("", files[0].dataUrl)
        assertEquals(AttachmentKind.Pdf, files[1].kind)
        assertEquals("notes.pdf", files[1].filename)
        assertEquals(AttachmentKind.Text, files[2].kind)
    }

    @Test
    fun contextImageMarkdown_isOmittedBecauseTheFilePartCarriesTheImage() {
        val message =
            T3MessageMapper.toMessage(
                T3ConversationMessage(
                    id = "m-user",
                    role = "user",
                    text = "Connected.\n![image.png](t3-context://attachment/att-image)",
                    streaming = false,
                    createdAt = null,
                    attachments =
                        listOf(
                            T3Attachment(
                                id = "att-image",
                                type = "image",
                                name = "image.png",
                                mimeType = "image/png",
                                sizeBytes = 12,
                            ),
                        ),
                ),
            )
        assertEquals("Connected.", message.text)
        assertEquals(
            "See ![diagram](https://example.com/a.png)",
            T3MessageMapper.withoutContextImages("See ![diagram](https://example.com/a.png)"),
        )
    }

    @Test
    fun imagePreview_fillsMatchingFileAsJpegDataUrl() {
        val message =
            T3MessageMapper.toMessage(
                T3ConversationMessage(
                    id = "m-user",
                    role = "user",
                    text = "Connected.",
                    streaming = false,
                    createdAt = null,
                    attachments =
                        listOf(
                            T3Attachment(
                                id = "att-image",
                                type = "image",
                                name = "image.png",
                                mimeType = "image/png",
                                sizeBytes = 229320,
                            ),
                        ),
                ),
            )
        val shown =
            T3MessageMapper.applyingImagePreviews(
                listOf(message),
                mapOf("att-image" to "data:image/jpeg;base64,abc"),
            )
        val file = shown.single().parts.filterIsInstance<MessagePart.File>().single()
        assertEquals(T3Assets.PREVIEW_MIME, file.mime)
        assertEquals("data:image/jpeg;base64,abc", file.dataUrl)
        assertEquals(AttachmentKind.Image, file.kind)
    }

    @Test
    fun assetCreateUrl_targetsAttachmentAndJoinsRelativePath() {
        val payload = T3Assets.createUrlPayload("att-image", "image.png", "image/png")
        val resource = payload["resource"]!!.jsonObject
        assertEquals("attachment", resource.string("_tag"))
        assertEquals("att-image", resource.string("attachmentId"))
        assertEquals("image.png", resource.string("fileName"))
        assertEquals("image/png", resource.string("mimeType"))
        assertEquals("inline", resource.string("disposition"))
        val relative =
            T3Assets.relativeUrl(
                T3Json.parser.parseToJsonElement("""{"relativeUrl":"/api/assets/tok/image.png","expiresAt":1}"""),
            )
        assertEquals("/api/assets/tok/image.png", relative)
        assertEquals(
            "http://127.0.0.1:3773/api/assets/tok/image.png",
            T3Urls.join("http://127.0.0.1:3773/", relative!!),
        )
        assertEquals(
            null,
            T3Assets.relativeUrl(T3Json.parser.parseToJsonElement("""{"relativeUrl":"https://evil.example/x"}""")),
        )
    }

    @Test
    fun visibleTurnItems_placeActivityBetweenText() {
        val messages = T3MessageMapper.toMessages(T3Json.threadSnapshot(activityFixture()))
        assertEquals(
            listOf("m-user", "activity:item-reason", "m-assistant", "activity:item-read"),
            messages.map { it.id },
        )
        assertEquals(MessageRole.User, messages[0].role)
        assertEquals("Connected.", messages[0].text)
        val image = messages[0].parts.filterIsInstance<MessagePart.File>().single()
        assertEquals("att-image", image.id)
        val reasoning = messages[1].parts.single()
        assertTrue(reasoning is MessagePart.Reasoning)
        assertEquals(MessageRole.Assistant, messages[1].role)
        assertEquals("", messages[1].text)
        assertEquals("Hi there", messages[2].text)
        assertEquals(1, messages.count { it.id == "m-assistant" })
        assertEquals(1, messages.count { it.text == "Hi there" })
        val tools = messages[3].parts.filterIsInstance<MessagePart.Tool>()
        assertEquals(listOf("read", "bash", "subagent"), tools.map { it.toolName })
    }

    @Test
    fun reasoningText_isSettledSoTheTimelineDrawsIt() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "messages": [],
                    "visibleTurnItems": [
                      {
                        "position": 0,
                        "visibility": "local",
                        "sourceThreadId": "thread-1",
                        "sourceItemId": "item-reason",
                        "item": {
                          "type": "reasoning",
                          "id": "item-reason",
                          "ordinal": 1,
                          "status": "running",
                          "text": "Need to look this up.",
                          "streaming": true,
                          "startedAt": "2026-10-03T12:00:01Z",
                          "completedAt": "2026-10-03T12:00:02Z"
                        }
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val message = T3MessageMapper.toMessages(snapshot).single()
        val reasoning = message.parts.single() as MessagePart.Reasoning
        assertEquals("item-reason", reasoning.id)
        assertEquals("Need to look this up.", reasoning.text)
        assertFalse(reasoning.isStreaming)
        assertEquals(T3MessageMapper.epochMs("2026-10-03T12:00:01Z"), reasoning.timeStartEpochMs)
        assertEquals(T3MessageMapper.epochMs("2026-10-03T12:00:02Z"), reasoning.timeEndEpochMs)
        assertEquals(T3MessageMapper.epochMs("2026-10-03T12:00:01Z"), message.sentAtEpochMs)
    }

    @Test
    fun turnItemType_matchesRegardlessOfCase() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "messages": [
                      { "id": "m-user", "role": "user", "text": "Hi" }
                    ],
                    "visibleTurnItems": [
                      {
                        "item": {
                          "type": "User_Message",
                          "id": "item-user",
                          "messageId": "m-user",
                          "text": "Hi"
                        }
                      },
                      {
                        "item": {
                          "type": "Reasoning",
                          "id": "item-reason",
                          "text": "Need to look this up.",
                          "streaming": false
                        }
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val messages = T3MessageMapper.toMessages(snapshot)
        assertEquals(listOf("m-user", "activity:item-reason"), messages.map { it.id })
        assertEquals("Hi", messages[0].text)
        val reasoning = messages[1].parts.single() as MessagePart.Reasoning
        assertEquals("Need to look this up.", reasoning.text)
    }

    @Test
    fun subagent_usesTitleAndChildThread() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "visibleTurnItems": [
                      {
                        "position": 0,
                        "visibility": "local",
                        "sourceThreadId": "thread-1",
                        "sourceItemId": "item-sub",
                        "item": {
                          "type": "subagent",
                          "id": "item-sub",
                          "ordinal": 1,
                          "title": "Explorer",
                          "status": "completed",
                          "prompt": "Find the bug",
                          "childThreadId": "thread-child"
                        }
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val tool =
            T3MessageMapper
                .toMessages(snapshot)
                .single()
                .parts
                .filterIsInstance<MessagePart.Tool>()
                .single()
        assertEquals("subagent", tool.toolName)
        assertEquals("Explorer", tool.input?.string("agent"))
        assertEquals("Find the bug", tool.input?.string("description"))
        assertEquals("thread-child", tool.metadata?.string("sessionId"))
    }

    @Test
    fun dynamicToolNames_mapReadFileAndCommand() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "turnItems": [
                      {
                        "type": "dynamic_tool",
                        "id": "item-read",
                        "ordinal": 1,
                        "toolName": "read_file",
                        "status": "completed",
                        "input": { "path": "src/Main.kt" }
                      },
                      {
                        "type": "command_execution",
                        "id": "item-bash",
                        "ordinal": 2,
                        "status": "completed",
                        "input": "vp check",
                        "output": "ok",
                        "exitCode": 0
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val tools =
            T3MessageMapper
                .toMessages(snapshot)
                .single()
                .parts
                .filterIsInstance<MessagePart.Tool>()
        assertEquals("read", tools[0].toolName)
        assertEquals("src/Main.kt", tools[0].input?.string("filePath"))
        assertEquals("bash", tools[1].toolName)
        assertEquals("vp check", tools[1].input?.string("command"))
        assertEquals("0", tools[1].metadata?.string("exit"))
        assertEquals("ok", tools[1].output)
    }

    @Test
    fun turnItemUpdated_insertsReasoningBetweenMessages() {
        val snapshot = T3Json.threadSnapshot(userAndAssistantFixture())
        val stream =
            T3Json.parseObject(
                """
                {
                  "kind": "event",
                  "event": {
                    "type": "turn-item.updated",
                    "payload": {
                      "type": "reasoning",
                      "id": "item-reason",
                      "ordinal": 1,
                      "status": "completed",
                      "text": "Thinking",
                      "streaming": false,
                      "startedAt": "2026-10-03T12:00:01Z"
                    }
                  }
                }
                """.trimIndent(),
            )
        val payload = (stream["event"] as JsonObject)["payload"]!!
        val item = requireNotNull(T3Json.turnItem(payload))
        val items = T3MessageMapper.upsertTurnItem(snapshot.turnItems, item)
        val messages = T3MessageMapper.transcriptMessages(snapshot.messages, items)
        assertEquals(listOf("m-user", "activity:item-reason", "m-assistant"), messages.map { it.id })
        val reasoning = messages[1].parts.single() as MessagePart.Reasoning
        assertEquals("item-reason", reasoning.id)
        assertEquals("Thinking", reasoning.text)
    }

    @Test
    fun messageUpdated_changesAssistantTextAndKeepsReasoning() {
        val snapshot = T3Json.threadSnapshot(activityFixture())
        val stream =
            T3Json.parseObject(
                """
                {
                  "kind": "event",
                  "event": {
                    "type": "message.updated",
                    "payload": {
                      "id": "m-assistant",
                      "role": "assistant",
                      "text": "Updated answer",
                      "streaming": false
                    }
                  }
                }
                """.trimIndent(),
            )
        val payload = (stream["event"] as JsonObject)["payload"]!!
        val updated = requireNotNull(T3Json.conversationMessage(payload))
        val messages = T3MessageMapper.upsertConversationMessage(snapshot.messages, updated)
        val derived = T3MessageMapper.transcriptMessages(messages, snapshot.turnItems)
        assertEquals("Updated answer", derived.first { it.id == "m-assistant" }.text)
        val reasoning = derived.first { it.id == "activity:item-reason" }.parts.single() as MessagePart.Reasoning
        assertEquals("Thinking between the lines.", reasoning.text)
        assertTrue(
            derived.any { message ->
                message.parts.filterIsInstance<MessagePart.Tool>().any { it.toolName == "subagent" }
            },
        )
    }

    @Test
    fun systemNotice_producesNoPart() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "messages": [
                      { "id": "m-user", "role": "user", "text": "Hello" },
                      { "id": "m-assistant", "role": "assistant", "text": "Hi there" }
                    ],
                    "visibleTurnItems": [
                      {
                        "position": 0,
                        "visibility": "local",
                        "sourceThreadId": "thread-1",
                        "sourceItemId": "item-user",
                        "item": {
                          "type": "user_message",
                          "id": "item-user",
                          "ordinal": 0,
                          "messageId": "m-user",
                          "text": "Hello"
                        }
                      },
                      {
                        "position": 1,
                        "visibility": "local",
                        "sourceThreadId": "thread-1",
                        "sourceItemId": "item-notice",
                        "item": {
                          "type": "system_notice",
                          "id": "item-notice",
                          "ordinal": 1,
                          "status": "completed",
                          "message": "Provider reconnected"
                        }
                      },
                      {
                        "position": 2,
                        "visibility": "local",
                        "sourceThreadId": "thread-1",
                        "sourceItemId": "item-assistant",
                        "item": {
                          "type": "assistant_message",
                          "id": "item-assistant",
                          "ordinal": 2,
                          "messageId": "m-assistant",
                          "text": "Hi there",
                          "streaming": false
                        }
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val messages = T3MessageMapper.toMessages(snapshot)
        assertEquals(listOf("m-user", "m-assistant"), messages.map { it.id })
        assertTrue(messages.none { it.text.contains("Provider reconnected") })
        assertTrue(messages.none { it.id.startsWith("activity:") })
    }

    @Test
    fun snapshotWithoutTurnItems_returnsConversationMessages() {
        val snapshot =
            T3Json.threadSnapshot(
                """
                {
                  "projection": {
                    "messages": [
                      { "id": "m-user", "role": "user", "text": "Hello" },
                      { "id": "m-assistant", "role": "assistant", "text": "Hi there", "streaming": true },
                      { "id": "m-system", "role": "system", "text": "System note" }
                    ],
                    "visibleTurnItems": [],
                    "turnItems": []
                  }
                }
                """.trimIndent(),
            )
        val messages = T3MessageMapper.toMessages(snapshot)
        assertEquals(listOf("m-user", "m-assistant", "m-system"), messages.map { it.id })
        assertEquals(listOf("Hello", "Hi there", "System note"), messages.map { it.text })
        assertTrue(messages.none { it.id.startsWith("activity:") })
        assertEquals(MessageRole.Assistant, messages[2].role)
    }

    private fun activityFixture(): String =
        """
        {
          "projection": {
            "messages": [
              {
                "id": "m-user",
                "role": "user",
                "text": "Connected.\n![image.png](t3-context://attachment/att-image)",
                "streaming": false,
                "createdAt": "2026-10-03T12:00:00Z",
                "attachments": [
                  {
                    "type": "image",
                    "id": "att-image",
                    "name": "image.png",
                    "mimeType": "image/png",
                    "sizeBytes": 12
                  }
                ]
              },
              {
                "id": "m-assistant",
                "role": "assistant",
                "text": "Hi there",
                "streaming": false,
                "createdAt": "2026-10-03T12:00:02Z"
              }
            ],
            "visibleTurnItems": [
              {
                "position": 0,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-user",
                "item": {
                  "type": "user_message",
                  "id": "item-user",
                  "ordinal": 0,
                  "messageId": "m-user",
                  "text": "Connected.\n![image.png](t3-context://attachment/att-image)",
                  "attachments": []
                }
              },
              {
                "position": 1,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-reason",
                "item": {
                  "type": "reasoning",
                  "id": "item-reason",
                  "ordinal": 1,
                  "status": "running",
                  "text": "Thinking between the lines.",
                  "streaming": true,
                  "startedAt": "2026-10-03T12:00:01Z",
                  "completedAt": "2026-10-03T12:00:01.500Z"
                }
              },
              {
                "position": 2,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-assistant",
                "item": {
                  "type": "assistant_message",
                  "id": "item-assistant",
                  "ordinal": 2,
                  "messageId": "m-assistant",
                  "text": "Hi there",
                  "streaming": false
                }
              },
              {
                "position": 3,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-read",
                "item": {
                  "type": "dynamic_tool",
                  "id": "item-read",
                  "ordinal": 3,
                  "toolName": "read_file",
                  "title": "Read notes.md",
                  "status": "completed",
                  "input": { "path": "notes.md" },
                  "startedAt": "2026-10-03T12:00:03Z"
                }
              },
              {
                "position": 4,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-bash",
                "item": {
                  "type": "command_execution",
                  "id": "item-bash",
                  "ordinal": 4,
                  "title": "Run checks",
                  "status": "completed",
                  "input": "vp check",
                  "output": "ok",
                  "exitCode": 0
                }
              },
              {
                "position": 5,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-sub",
                "item": {
                  "type": "subagent",
                  "id": "item-sub",
                  "ordinal": 5,
                  "title": "Explorer",
                  "status": "completed",
                  "prompt": "Find the bug",
                  "childThreadId": "thread-child"
                }
              },
              {
                "position": 6,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-assistant",
                "item": {
                  "type": "assistant_message",
                  "id": "item-assistant-again",
                  "ordinal": 6,
                  "messageId": "m-assistant",
                  "text": "Hi there again",
                  "streaming": false
                }
              },
              { "position": 7, "item": null },
              { "item": { "type": "reasoning", "text": "missing id" } }
            ]
          }
        }
        """.trimIndent()

    private fun userAndAssistantFixture(): String =
        """
        {
          "projection": {
            "messages": [
              { "id": "m-user", "role": "user", "text": "Hello", "createdAt": "2026-10-03T12:00:00Z" },
              { "id": "m-assistant", "role": "assistant", "text": "Hi there", "streaming": false }
            ],
            "visibleTurnItems": [
              {
                "position": 0,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-user",
                "item": {
                  "type": "user_message",
                  "id": "item-user",
                  "ordinal": 0,
                  "messageId": "m-user",
                  "text": "Hello"
                }
              },
              {
                "position": 1,
                "visibility": "local",
                "sourceThreadId": "thread-1",
                "sourceItemId": "item-assistant",
                "item": {
                  "type": "assistant_message",
                  "id": "item-assistant",
                  "ordinal": 2,
                  "messageId": "m-assistant",
                  "text": "Hi there",
                  "streaming": false
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
