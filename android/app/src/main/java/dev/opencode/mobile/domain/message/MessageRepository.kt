package dev.opencode.mobile.domain.message

interface MessageRepository {
  suspend fun listMessages(sessionId: String): List<Message>

  suspend fun getMessage(sessionId: String, messageId: String): Message

  suspend fun sendMessage(sessionId: String, text: String): Message

  suspend fun sendPromptAsync(sessionId: String, text: String)
}
