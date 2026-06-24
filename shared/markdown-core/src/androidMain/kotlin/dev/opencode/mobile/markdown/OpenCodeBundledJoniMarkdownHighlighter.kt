package dev.opencode.mobile.markdown

import android.content.Context

object OpenCodeBundledJoniMarkdownHighlighter {
    fun create(resourceReader: OpenCodeTextMateResourceReader): OpenCodeSyntaxHighlighter {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(resourceReader)
                .bundle

        return OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend),
        )
    }

    fun createFromContext(context: Context): OpenCodeSyntaxHighlighter =
        create(assetResourceReader(context.applicationContext))

    fun createFromApplicationContext(): OpenCodeSyntaxHighlighter =
        createFromContext(OpenCodeAndroidMarkdownAssets.requireApplicationContext())

    fun default(): OpenCodeCodeHighlighter = lazyDefault

    private val lazyDefault: OpenCodeSyntaxHighlighter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { createFromApplicationContext() }.getOrElse { create(classpathResourceReader()) }
    }

    private fun classpathResourceReader(): OpenCodeTextMateResourceReader =
        OpenCodeTextMateResourceReader { resourcePath ->
            OpenCodeBundledJoniMarkdownHighlighter::class.java.classLoader?.getResource(resourcePath)?.readText()
        }

    private fun assetResourceReader(context: Context): OpenCodeTextMateResourceReader =
        OpenCodeTextMateResourceReader { resourcePath ->
            runCatching {
                context.assets.open(resourcePath).bufferedReader().use { reader -> reader.readText() }
            }.getOrNull()
        }
}
