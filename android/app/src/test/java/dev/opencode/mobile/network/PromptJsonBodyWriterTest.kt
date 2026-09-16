package dev.opencode.mobile.network

import dev.opencode.mobile.data.remote.dto.CommandFilePartDto
import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
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
}
