package dev.opencode.mobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.AgentsApi
import dev.opencode.mobile.data.remote.CommandsApi
import dev.opencode.mobile.data.remote.McpApi
import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.data.remote.ModelsApi
import dev.opencode.mobile.data.remote.PtyApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.network.OpencodeHttpClient
import kotlinx.serialization.json.Json
import javax.inject.Singleton

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
    fun provideSessionsApi(
        httpClient: OpencodeHttpClient,
        json: Json,
    ): SessionsApi = SessionsApi(httpClient, json)

    @Provides
    @Singleton
    fun provideMessagesApi(
        httpClient: OpencodeHttpClient,
        json: Json,
    ): MessagesApi = MessagesApi(httpClient, json)

    @Provides
    @Singleton
    fun provideModelsApi(
        httpClient: OpencodeHttpClient,
        json: Json,
    ): ModelsApi = ModelsApi(httpClient, json)

    @Provides
    @Singleton
    fun provideCommandsApi(
        httpClient: OpencodeHttpClient,
        json: Json,
    ): CommandsApi = CommandsApi(httpClient, json)

    @Provides
    @Singleton
    fun providePtyApi(
        httpClient: OpencodeHttpClient,
        json: Json,
    ): PtyApi = PtyApi(httpClient, json)

    @Provides
    @Singleton
    fun provideAgentsApi(httpClient: OpencodeHttpClient): AgentsApi = AgentsApi(httpClient)

    @Provides
    @Singleton
    fun provideMcpApi(httpClient: OpencodeHttpClient): McpApi = McpApi(httpClient)
}
