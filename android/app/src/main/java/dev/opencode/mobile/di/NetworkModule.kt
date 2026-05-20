package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
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
  ): HttpClient =
    HttpClient(OkHttp) {
      install(ContentNegotiation) { json(json) }
      defaultRequest { url(connection.apiBaseUrl) }
      val password = connection.password
      if (!password.isNullOrEmpty()) {
        install(Auth) {
          basic {
            credentials {
              BasicAuthCredentials(username = connection.username, password = password)
            }
            sendWithoutRequest { true }
          }
        }
      }
    }

  @Provides
  @Singleton
  fun provideSessionsApi(httpClient: HttpClient): SessionsApi = SessionsApi(httpClient)

  @Provides
  @Singleton
  fun provideMessagesApi(httpClient: HttpClient): MessagesApi = MessagesApi(httpClient)
}
