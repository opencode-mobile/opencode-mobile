package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.message.DefaultMessageRepository
import dev.opencode.mobile.domain.message.DisplayMessagePipeline
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageSync
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MessageModule {
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository

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
            api: MessagesApi,
            adapter: MessageDomainAdapter,
            store: InMemoryMessageStore,
            eventStream: ForegroundEventStream,
            liveStoreScope: LiveStoreScope,
        ): MessageSync =
            MessageSync(
                listMessages = { sessionId, directory ->
                    runCatching { api.listMessages(sessionId, directory).map(adapter::toMessage) }
                        .getOrElse { throw adapter.mapError(it) }
                },
                store = store,
                eventStream = eventStream,
                liveStoreScope = liveStoreScope,
            )
    }
}
