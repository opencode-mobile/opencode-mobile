package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.message.DefaultMessageRepository
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageRepository
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
    }
}
