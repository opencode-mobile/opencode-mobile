package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarkdownModule {
    @Provides
    @Singleton
    fun provideMarkdownCodeHighlighter(
        @ApplicationContext context: Context,
    ): OpenCodeCodeHighlighter =
        OpenCodeBundledJoniMarkdownHighlighter.createFromContext(context)
}
