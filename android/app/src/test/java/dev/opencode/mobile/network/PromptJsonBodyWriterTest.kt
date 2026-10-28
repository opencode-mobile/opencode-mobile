package dev.opencode.mobile.network

import dev.opencode.mobile.data.remote.dto.CommandFilePartDto
import dev.opencode.mobile.data.remote.dto.FileReferenceSourceDto
import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileMentionDto
import dev.opencode.mobile.data.remote.dto.V2PromptRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptSkillDto
import dev.opencode.mobile.data.remote.dto.V2PromptSourceDto
import dev.opencode.mobile.data.remote.dto.V2RunCommandRequestDto
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptJsonBodyWriterTest {
    @Test
    fun writeV1Prompt_streamsFileBackedDataUrl() {
        val source =
            File(System.getProperty("java.io.tmpdir"), "prompt-writer-source.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val attachment =
            Attachment(
                id = "a1",
                filename = "note.txt",
                mime = "text/plain",
                kind = AttachmentKind.Text,
                dataUrl = "",
                byteCount = 4,
                localPath = source.absolutePath,
            )
        val body =
            SendMessageRequestDto(
                parts =
                    listOf(
                        SendMessagePartDto(type = "text", text = "Hi"),
                        SendMessagePartDto(type = "file", mime = "text/plain", url = "", filename = "note.txt"),
                    ),
            )

        val file =
            PromptJsonBodyWriter.writeV1Prompt(
                body,
                listOf(attachment),
                File(System.getProperty("java.io.tmpdir"), "prompt-writer-out"),
            )
        val json = file.readText()

        assertTrue(json.contains("\"text\":\"Hi\""))
        assertTrue(json.contains("\"filename\":\"note.txt\""))
        assertTrue(json.contains("data:text/plain;base64,AQIDBA=="))
        assertEquals(4, source.length())
    }

    @Test
    fun writeV1Prompt_writesFileMentionUrlLiterallyAndKeepsAttachmentIndex() {
        val source =
            File(System.getProperty("java.io.tmpdir"), "prompt-writer-attachment.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val attachment =
            Attachment(
                id = "a1",
                filename = "note.txt",
                mime = "text/plain",
                kind = AttachmentKind.Text,
                dataUrl = "",
                byteCount = 4,
                localPath = source.absolutePath,
            )
        val body =
            SendMessageRequestDto(
                parts =
                    listOf(
                        SendMessagePartDto(
                            type = "file",
                            mime = "text/plain",
                            url = "file:///repo/src/App.kt",
                            filename = "App.kt",
                            fileSource =
                                FileReferenceSourceDto(
                                    path = "src/App.kt",
                                    value = "@src/App.kt",
                                    start = 4,
                                    end = 15,
                                ),
                        ),
                        SendMessagePartDto(type = "file", mime = "text/plain", url = "", filename = "note.txt"),
                    ),
            )
        val json =
            PromptJsonBodyWriter
                .writeV1Prompt(
                    body,
                    listOf(attachment),
                    File(System.getProperty("java.io.tmpdir"), "prompt-writer-out"),
                ).readText()
        assertTrue(json.contains("\"url\":\"file:///repo/src/App.kt\""))
        assertTrue(json.contains("\"path\":\"src/App.kt\""))
        assertTrue(json.contains("\"value\":\"@src/App.kt\""))
        assertTrue(json.contains("data:text/plain;base64,AQIDBA=="))
        assertTrue(json.indexOf("file:///repo/src/App.kt") < json.indexOf("data:text/plain;base64,AQIDBA=="))
    }

    @Test
    fun writeV2Prompt_writesMentionUriLiterallyAfterAttachment() {
        val source =
            File(System.getProperty("java.io.tmpdir"), "prompt-writer-v2.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val attachment =
            Attachment(
                id = "a1",
                filename = "note.txt",
                mime = "text/plain",
                kind = AttachmentKind.Text,
                dataUrl = "",
                byteCount = 4,
                localPath = source.absolutePath,
            )
        val body =
            V2PromptRequestDto(
                text = "see @src/App.kt",
                files =
                    listOf(
                        dev.opencode.mobile.data.remote.dto.V2PromptFileDto(
                            uri = "",
                            name = "note.txt",
                            mime = "text/plain",
                        ),
                    ),
                delivery = "steer",
                fileMentions =
                    listOf(
                        V2PromptFileMentionDto(
                            uri = "file:///repo/src/App.kt",
                            name = "App.kt",
                            mention = V2PromptSourceDto(start = 4, end = 15, text = "@src/App.kt"),
                        ),
                    ),
            )
        val json =
            PromptJsonBodyWriter
                .writeV2Prompt(
                    body,
                    listOf(attachment),
                    File(System.getProperty("java.io.tmpdir"), "prompt-writer-out"),
                ).readText()
        assertTrue(json.contains("data:text/plain;base64,AQIDBA=="))
        assertTrue(json.contains("\"uri\":\"file:///repo/src/App.kt\""))
        assertTrue(json.contains("\"text\":\"@src/App.kt\""))
        assertTrue(json.indexOf("data:text/plain;base64,AQIDBA==") < json.indexOf("file:///repo/src/App.kt"))
    }

    @Test
    fun writeV2Prompt_writesMetadataBeforeDelivery() {
        val body =
            V2PromptRequestDto(
                text = "look\nAttached file: `/tmp/a`",
                metadata =
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "attachments",
                            kotlinx.serialization.json.buildJsonArray {
                                add(
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("name", "a.bin")
                                        put("mime", "application/octet-stream")
                                        put("path", "/tmp/a")
                                    },
                                )
                            },
                        )
                    },
                delivery = "steer",
            )
        val json =
            PromptJsonBodyWriter
                .writeV2Prompt(body, emptyList(), File(System.getProperty("java.io.tmpdir"), "prompt-writer-out"))
                .readText()
        assertTrue(
            json,
            json.contains(
                "\"metadata\":{\"attachments\":[{\"name\":\"a.bin\",\"mime\":\"application/octet-stream\",\"path\":\"/tmp/a\"}]},\"delivery\":\"steer\"",
            ),
        )
        kotlinx.serialization.json.Json
            .parseToJsonElement(json)
    }

    @Test
    fun writeV2Prompt_writesSkillWithMention() {
        val body =
            V2PromptRequestDto(
                text = "fix /tdd now",
                skills = listOf(V2PromptSkillDto(id = "tdd", mention = V2PromptSourceDto(start = 4, end = 8, text = "/tdd"))),
                delivery = "steer",
            )
        val json =
            PromptJsonBodyWriter
                .writeV2Prompt(body, emptyList(), File(System.getProperty("java.io.tmpdir"), "prompt-writer-out"))
                .readText()
        assertTrue(json, json.contains("\"skills\":[{\"id\":\"tdd\",\"mention\":{\"start\":4,\"end\":8,\"text\":\"/tdd\"}}]"))
    }

    @Test
    fun writeV2Command_writesSkillIdsWithoutMention() {
        val body =
            V2RunCommandRequestDto(
                name = "init",
                text = "fix /tdd",
                delivery = "steer",
                skills = listOf(V2PromptSkillDto(id = "tdd")),
            )
        val json =
            PromptJsonBodyWriter
                .writeV2Command(body, emptyList(), File(System.getProperty("java.io.tmpdir"), "prompt-writer-out"))
                .readText()
        assertTrue(json, json.contains("\"skills\":[{\"id\":\"tdd\"}]"))
    }

    @Test
    fun writeV1Command_includesFilePartType() {
        val source =
            File(System.getProperty("java.io.tmpdir"), "command-writer-source.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val attachment =
            Attachment(
                id = "a1",
                filename = "shot.png",
                mime = "image/png",
                kind = AttachmentKind.Image,
                dataUrl = "",
                byteCount = 4,
                localPath = source.absolutePath,
            )
        val body =
            RunCommandRequestDto(
                command = "publish-prototype",
                arguments = "improve this UI",
                parts =
                    listOf(
                        CommandFilePartDto(mime = "image/png", url = "", filename = "shot.png"),
                    ),
            )

        val file =
            PromptJsonBodyWriter.writeV1Command(
                body,
                listOf(attachment),
                File(System.getProperty("java.io.tmpdir"), "command-writer-out"),
            )
        val json = file.readText()

        assertTrue(json.contains("\"command\":\"publish-prototype\""))
        assertTrue(json.contains("\"type\":\"file\""))
        assertTrue(json.contains("\"filename\":\"shot.png\""))
        assertTrue(json.contains("data:image/png;base64,AQIDBA=="))
    }

    @Test
    fun writers_sendClientMessageId() {
        val directory = File(System.getProperty("java.io.tmpdir"), "prompt-writer-ids")
        val v1Prompt =
            PromptJsonBodyWriter.writeV1Prompt(
                SendMessageRequestDto(parts = listOf(SendMessagePartDto(type = "text", text = "Hi")), messageId = "msg_v1"),
                emptyList(),
                directory,
            )
        val v2Prompt =
            PromptJsonBodyWriter.writeV2Prompt(
                V2PromptRequestDto(id = "msg_v2", text = "Hi", delivery = "steer"),
                emptyList(),
                directory,
            )
        val v1Command =
            PromptJsonBodyWriter.writeV1Command(
                RunCommandRequestDto(command = "review", arguments = "", messageId = "msg_cmd"),
                emptyList(),
                directory,
            )

        assertTrue(v1Prompt.readText().contains("\"messageID\":\"msg_v1\""))
        assertTrue(v2Prompt.readText().contains("\"id\":\"msg_v2\""))
        assertTrue(v1Command.readText().contains("\"messageID\":\"msg_cmd\""))
    }
}
