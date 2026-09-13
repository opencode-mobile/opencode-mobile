package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.message.DefaultMessageRepository
import dev.opencode.mobile.domain.message.DisplayMessagePipeline
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageRemote
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageHistoryQuery
import dev.opencode.mobile.domain.message.MessageHistoryWindow
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.message.ProtocolSelectedMessageRemote
import dev.opencode.mobile.domain.message.SessionMessageEngine
import dev.opencode.mobile.domain.sessioncache.SessionCache
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MessageModule {
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository

    @Binds
    @Singleton
    abstract fun bindMessageRemote(impl: ProtocolSelectedMessageRemote): MessageRemote

    companion object {
        @Provides
        @Singleton
        fun provideMessageDomainAdapter(): MessageDomainAdapter = MessageDomainAdapter()

        @Provides
        @Singleton
        fun provideDisplayMessagePipeline(
            messageSync: MessageSync,
            contentPreparer: MessageContentPreparer,
        ): DisplayMessagePipeline =
            DisplayMessagePipeline(
                messageSync = messageSync,
                contentPreparer = contentPreparer,
            )

        @Provides
        @Singleton
        fun provideMessageSync(
            messageRemote: MessageRemote,
            adapter: MessageDomainAdapter,
            engine: SessionMessageEngine,
            eventStream: ForegroundEventStream,
            liveStoreScope: LiveStoreScope,
            activeConnectionManager: dev.opencode.mobile.network.ActiveConnectionManager,
            sessionCache: SessionCache,
            worldSignals: dev.opencode.mobile.domain.event.WorldSignals,
        ): MessageSync =
            MessageSync(
                listMessages = { sessionId, directory ->
                    runCatching { messageRemote.listMessages(sessionId, directory) }
                        .getOrElse { throw adapter.mapError(it) }
                },
                listMessageTail = { sessionId, directory ->
                    runCatching {
                        messageRemote.listMessagePage(sessionId, directory, MessageHistoryQuery.tail())
                    }.getOrElse { throw adapter.mapError(it) }
                },
                listOlderMessages = { sessionId, directory, cursor ->
                    runCatching {
                        messageRemote.listMessagePage(
                            sessionId,
                            directory,
                            MessageHistoryQuery.older(cursor),
                        )
                    }.getOrElse { throw adapter.mapError(it) }
                },
                listSplitTurnMessages = { sessionId, directory, cursor ->
                    runCatching {
                        messageRemote.listMessagePage(
                            sessionId,
                            directory,
                            MessageHistoryQuery.older(cursor, MessageHistoryWindow.TAIL_LIMIT),
                        )
                    }.getOrElse { throw adapter.mapError(it) }
                },
                engine = engine,
                eventStream = eventStream,
                liveStoreScope = liveStoreScope,
                worldSignals = worldSignals,
                connectionProfileIdProvider = {
                    val profile = activeConnectionManager.currentProfile()
                    val profileId = profile?.id?.ifBlank { null }
                    if (profileId != null) {
                        profileId
                    } else {
                        val connection = activeConnectionManager.current()
                        if (connection == null) {
                            "local"
                        } else {
                            "${connection.baseUrl.lowercase()}|${connection.username.lowercase()}"
                        }
                    }
                },
                sessionCache = sessionCache,
            )
    }
}
