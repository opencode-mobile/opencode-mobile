package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeJoniRegexBackend
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateGrammarBundleLoader
import dev.opencode.mobile.markdown.OpenCodeTextMateNativeTokenizer
import dev.opencode.mobile.markdown.OpenCodeTextMateResourceReader
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarkdownModule {
    @Provides
    @Singleton
    fun provideMarkdownCodeHighlighter(
        @ApplicationContext context: Context,
    ): OpenCodeCodeHighlighter {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(
                    OpenCodeTextMateResourceReader { resourcePath ->
                        runCatching {
                            context.assets.open(resourcePath).bufferedReader().use { reader -> reader.readText() }
                        }.getOrNull()
                    },
                ).bundle

        return OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend),
        )
    }
}
