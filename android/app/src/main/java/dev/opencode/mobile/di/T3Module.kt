package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.t3.OkHttpT3Http
import dev.opencode.mobile.domain.t3.T3Http
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class T3Module {
    @Binds
    @Singleton
    abstract fun bindT3Http(impl: OkHttpT3Http): T3Http
}
