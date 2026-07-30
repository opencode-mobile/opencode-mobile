package dev.opencode.mobile.markdown

import android.content.Context

object OpenCodeBundledJoniMarkdownHighlighter {
    fun create(
        resourceReader: OpenCodeTextMateResourceReader,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    ): OpenCodeSyntaxHighlighter {
        val cacheCoordinator = TextMateWeightedCacheCoordinator()
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(resourceReader, metricsSink)
                .bundle

        return OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer =
                OpenCodeTextMateNativeTokenizer(
                    regexBackend = OpenCodeJoniRegexRuntime(cacheCoordinator),
                    metricsSink = metricsSink,
                    cacheCoordinator = cacheCoordinator,
                ),
            metricsSink = metricsSink,
        )
    }

    fun createFromContext(context: Context): OpenCodeSyntaxHighlighter =
        create(assetResourceReader(context.applicationContext))

    fun packagedPrimaryLanguages(context: Context): List<String> =
        TextMatePackagedLanguages.loadPrimaryCanonicalNames(
            assetResourceReader(context.applicationContext),
        )

    fun createFromApplicationContext(): OpenCodeSyntaxHighlighter =
        createFromContext(OpenCodeAndroidMarkdownAssets.requireApplicationContext())

    fun default(): OpenCodeCodeHighlighter =
        runCatching { createFromApplicationContext() }.getOrElse { create(classpathResourceReader()) }

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
