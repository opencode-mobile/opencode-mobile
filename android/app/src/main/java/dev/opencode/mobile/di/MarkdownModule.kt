package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.HighlightingEngineSeam
import dev.opencode.mobile.domain.textmate.AndroidTextMateHighlightingEngine
import dev.opencode.mobile.domain.textmate.TextMateLogbook
import dev.opencode.mobile.domain.textmate.TextMateLogbookRecorder
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarkdownModule {
    @Provides
    @Singleton
    fun provideAndroidTextMateHighlightingEngine(
        @ApplicationContext context: Context,
    ): AndroidTextMateHighlightingEngine =
        AndroidTextMateHighlightingEngine(
            delegateFactory = {
                OpenCodeTextMateHighlightingEngine(
                    OpenCodeBundledJoniMarkdownHighlighter.createFromContext(context),
                )
            },
            ownerDispatcher = Dispatchers.Default.limitedParallelism(1),
        )

    /** TextMate, or in developer builds the engine the Highlighting engine setting picks (#872). */
    @Provides
    @Singleton
    fun provideTextMateHighlightingEngine(textMate: AndroidTextMateHighlightingEngine): TextMateHighlightingEngine =
        HighlightingEngineSeam.chatEngine(textMate)

    @Provides
    @Singleton
    fun provideTextMateLogbook(
        @ApplicationContext context: Context,
    ): TextMateLogbook =
        TextMateLogbook(
            file = TextMateLogbook.fileIn(context.filesDir),
            dispatcher = Dispatchers.IO,
        )

    @Provides
    fun provideTextMateLogbookRecorder(logbook: TextMateLogbook): TextMateLogbookRecorder = logbook
}
