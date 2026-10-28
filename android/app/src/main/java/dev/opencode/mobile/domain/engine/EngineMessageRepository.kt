package dev.opencode.mobile.domain.engine

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.DefaultMessageRepository
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.OlderDisplayLoadResult
import dev.opencode.mobile.domain.message.OlderLoadResult
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.ShellCommandOptions
import dev.opencode.mobile.domain.t3.T3MessageRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineMessageRepository
    @Inject
    constructor(
        private val opencode: DefaultMessageRepository,
        private val t3: T3MessageRepository,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : MessageRepository {
        private fun delegate(): MessageRepository =
            if (activeConnectionManager.currentProfile()?.engine == ConnectionEngine.T3) t3 else opencode

        override fun observeMessages(
            sessionId: String,
            directory: String?,
        ): Flow<MessageListUpdate> = delegate().observeMessages(sessionId, directory)

        override fun observeDisplayMessages(
            sessionId: String,
            directory: String?,
        ): Flow<DisplayMessageListUpdate> = delegate().observeDisplayMessages(sessionId, directory)

        override fun observeCatchUpChrome(): Flow<Boolean> = delegate().observeCatchUpChrome()

        override fun observeCatchUpChrome(
            sessionId: String,
            directory: String?,
        ): Flow<Boolean> = delegate().observeCatchUpChrome(sessionId, directory)

        override suspend fun refreshMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = delegate().refreshMessages(sessionId, directory)

        override suspend fun refreshDisplayMessages(
            sessionId: String,
            directory: String?,
        ): List<DisplayMessage> = delegate().refreshDisplayMessages(sessionId, directory)

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
        ): DisplayMessage = delegate().prepareOptimisticUserMessage(id, text)

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
            attachments: List<Attachment>,
        ): DisplayMessage = delegate().prepareOptimisticUserMessage(id, text, attachments)

        override fun clearMessageCache() = delegate().clearMessageCache()

        override suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = delegate().listMessages(sessionId, directory)

        override suspend fun loadOlderMessages(
            sessionId: String,
            directory: String?,
        ): OlderLoadResult = delegate().loadOlderMessages(sessionId, directory)

        override suspend fun loadOlderDisplayMessages(
            sessionId: String,
            directory: String?,
        ): OlderDisplayLoadResult = delegate().loadOlderDisplayMessages(sessionId, directory)

        override suspend fun sendPromptAsync(
            sessionId: String,
            text: String,
            directory: String?,
        ) = delegate().sendPromptAsync(sessionId, text, directory)

        override suspend fun sendPromptAsync(
            sessionId: String,
            options: PromptSendOptions,
            directory: String?,
        ) = delegate().sendPromptAsync(sessionId, options, directory)

        override suspend fun runShellCommand(
            sessionId: String,
            options: ShellCommandOptions,
            directory: String?,
        ) = delegate().runShellCommand(sessionId, options, directory)
    }
