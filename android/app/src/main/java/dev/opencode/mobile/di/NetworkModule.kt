package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
  @Provides
  @Singleton
  fun provideServerConnection(
    @ApplicationContext context: Context,
  ): ServerConnection = ServerConnection.fromAppConfig(context)

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
  fun provideHttpClient(
    connection: ServerConnection,
    json: Json,
  ): HttpClient = KtorClientFactory.create(connection, json)

  @Provides
  @Singleton
  fun provideSessionsApi(httpClient: HttpClient, json: Json): SessionsApi = SessionsApi(httpClient, json)

  @Provides
  @Singleton
  fun provideMessagesApi(httpClient: HttpClient, json: Json): MessagesApi = MessagesApi(httpClient, json)
}
