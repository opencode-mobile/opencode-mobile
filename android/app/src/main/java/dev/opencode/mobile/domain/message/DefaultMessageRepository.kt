package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import javax.inject.Inject

class DefaultMessageRepository
@Inject
constructor(
  private val api: MessagesApi,
  private val adapter: MessageDomainAdapter,
) : MessageRepository {
  override suspend fun listMessages(sessionId: String, directory: String?): List<Message> =
    runCatching { api.listMessages(sessionId, directory).map(adapter::toMessage) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun getMessage(sessionId: String, messageId: String, directory: String?): Message =
    runCatching { adapter.toMessage(api.getMessage(sessionId, messageId, directory)) }
      .getOrElse { throw adapter.mapError(it) }

  override suspend fun sendMessage(sessionId: String, text: String, directory: String?): Message =
    runCatching {
      adapter.toMessage(api.sendMessage(sessionId, adapter.toSendRequest(text), directory))
    }.getOrElse { throw adapter.mapError(it) }

  override suspend fun sendPromptAsync(sessionId: String, text: String, directory: String?) {
    runCatching { api.sendPromptAsync(sessionId, adapter.toSendRequest(text), directory) }
      .getOrElse { throw adapter.mapError(it) }
  }
}
