package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.event.DefaultForegroundEventStream
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
abstract class EventStreamModule {
  @Binds
  @Singleton
  abstract fun bindForegroundEventStream(impl: DefaultForegroundEventStream): ForegroundEventStream

  companion object {
    @Provides
    @Singleton
    @Named("sse")
    fun provideSseHttpClient(
      connection: ServerConnection,
      json: Json,
    ): HttpClient = KtorClientFactory.create(connection, json, contentNegotiation = false)

    @Provides
    @Singleton
    fun provideEventsApi(
      @Named("sse") httpClient: HttpClient,
    ): EventsApi = EventsApi(httpClient)

    @Provides
    @Singleton
    fun provideEventDomainAdapter(json: Json): EventDomainAdapter = EventDomainAdapter(json)
  }
}
