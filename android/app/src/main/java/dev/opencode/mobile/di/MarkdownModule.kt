package dev.opencode.mobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.markdown.HighlightCoreHighlightingEngine
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarkdownModule {
    /**
     * Session Chat code fences on the #872 highlight-core library. It runs on the caller's thread
     * (the message preparer's worker) under its own per-call deadline.
     */
    @Provides
    @Singleton
    fun provideTextMateHighlightingEngine(): TextMateHighlightingEngine = HighlightCoreHighlightingEngine()
}
