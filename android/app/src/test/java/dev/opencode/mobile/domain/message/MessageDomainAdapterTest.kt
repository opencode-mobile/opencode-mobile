package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    assertEquals("Tool: glob", message.text)
  }
}
