package dev.opencode.mobile.network

import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptJsonBodyWriterTest {
    @Test
    fun writeV1Prompt_streamsFileBackedDataUrl() {
        val source = File(System.getProperty("java.io.tmpdir"), "prompt-writer-source.bin").apply {
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
}
