package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.HealthApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProjectModule {
  @Binds
  @Singleton
  abstract fun bindProjectRepository(impl: DefaultProjectRepository): ProjectRepository

  companion object {
    @Provides
    @Singleton
    fun provideHealthApi(httpClient: HttpClient): HealthApi = HealthApi(httpClient)

    @Provides
    @Singleton
    fun provideProjectsApi(httpClient: HttpClient): ProjectsApi = ProjectsApi(httpClient)

    @Provides
    @Singleton
    fun provideProjectDomainAdapter(): ProjectDomainAdapter = ProjectDomainAdapter()
  }
}
