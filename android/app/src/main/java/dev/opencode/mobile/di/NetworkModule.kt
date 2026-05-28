package dev.opencode.mobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.network.OpencodeHttpClient
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
  @Provides
  @Singleton
  fun provideJson(): Json =
    Json {
      ignoreUnknownKeys = true
      isLenient = true
      explicitNulls = false
    }

  @Provides
  @Singleton
  fun provideSessionsApi(httpClient: OpencodeHttpClient, json: Json): SessionsApi =
    SessionsApi(httpClient, json)

  @Provides
  @Singleton
  fun provideMessagesApi(httpClient: OpencodeHttpClient, json: Json): MessagesApi =
    MessagesApi(httpClient, json)
}
