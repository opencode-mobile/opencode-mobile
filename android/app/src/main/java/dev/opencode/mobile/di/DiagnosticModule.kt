package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticModule {
    @Provides
    @Singleton
    fun provideDiagnosticLogBuffer(
        @ApplicationContext context: Context,
    ): DiagnosticLogBuffer {
        val buffer =
            DiagnosticLogBuffer(
                file = DiagnosticLogBuffer.fileIn(context.filesDir),
            )
        DiagnosticLogBuffer.bindShared(buffer)
        return buffer
    }
}
