package dev.opencode.mobile.markdown

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

internal actual fun openCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter = appleMarkdownHighlighter

private val appleMarkdownHighlighter: OpenCodeCodeHighlighter by lazy {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(OpenCodeAppleTextMateResourceReader)
                .bundle

        OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
        )
}

private object OpenCodeAppleTextMateResourceReader : OpenCodeTextMateResourceReader {
    @OptIn(ExperimentalForeignApi::class)
    override fun readText(resourcePath: String): String? {
        val directory = resourcePath.substringBeforeLast("/", missingDelimiterValue = "")
        val fileName = resourcePath.substringAfterLast("/")
        val extension = fileName.substringAfterLast(".", missingDelimiterValue = "")
        val name = if (extension.isEmpty()) fileName else fileName.removeSuffix(".$extension")
        val type = extension.takeIf { it.isNotEmpty() }
        val searchDirectory = directory.takeIf { it.isNotEmpty() }
        val bundles = buildList {
            add(NSBundle.mainBundle)
            addAll(NSBundle.allBundles.filterIsInstance<NSBundle>())
            addAll(NSBundle.allFrameworks.filterIsInstance<NSBundle>())
        }.distinctBy { bundle -> bundle.bundlePath }

        bundles.forEach { bundle ->
            val path = bundle.pathForResource(name, ofType = type, inDirectory = searchDirectory) ?: return@forEach
            return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as String?
        }

        return null
    }
}
