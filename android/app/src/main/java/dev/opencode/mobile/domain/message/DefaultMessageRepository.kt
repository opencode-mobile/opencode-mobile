package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import javax.inject.Inject

class DefaultMessageRepository
@Inject
constructor(
  private val api: MessagesApi,
  private val adapter: MessageDomainAdapter,
) : MessageRepository {
  override suspend fun listMessages(sessionId: String): List<Message> =
    runCatching { api.listMessages(sessionId).map(adapter::toMessage) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun getMessage(sessionId: String, messageId: String): Message =
    runCatching { adapter.toMessage(api.getMessage(sessionId, messageId)) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun sendMessage(sessionId: String, text: String): Message =
    runCatching {
      adapter.toMessage(api.sendMessage(sessionId, adapter.toSendRequest(text)))
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun sendPromptAsync(sessionId: String, text: String) {
    runCatching { api.sendPromptAsync(sessionId, adapter.toSendRequest(text)) }
      .getOrElse { throw adapter.mapError(it) }
  }
}
