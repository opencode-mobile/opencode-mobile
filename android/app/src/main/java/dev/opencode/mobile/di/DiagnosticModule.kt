package dev.opencode.mobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticModule {
    @Provides
    @Singleton
    fun provideDiagnosticLogBuffer(): DiagnosticLogBuffer = DiagnosticLogBuffer.shared
}
