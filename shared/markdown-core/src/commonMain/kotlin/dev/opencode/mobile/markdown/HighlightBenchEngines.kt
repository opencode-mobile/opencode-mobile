package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan

/** The engines a Highlighting Lab can benchmark and compare, in the order it lists them. */
object HighlightBenchEngines {
    /** TextMate first, so comparisons use it as the baseline, then highlight-core. Each call builds new engines. */
    fun available(): List<HighlightBenchEngine> =
        listOf(TextMateHighlightBenchEngine(), HighlightCoreHighlightBenchEngine, SemanticFallbackHighlightBenchEngine)

    fun engine(id: String): HighlightBenchEngine? = available().firstOrNull { it.id == id }
}

/**
 * [OpenCodeSemanticFallbackHighlighter] behind the benchmark seam: what the File Viewer shows for
 * files over the TextMate scheduler cap. It is stateless and never checks cancellation, so every
 * instance is the same and a slow call shows up as ignoring cancel.
 */
object SemanticFallbackHighlightBenchEngine : HighlightBenchEngine {
    override val id: String = "semantic-fallback"

    override val displayName: String = "Semantic fallback"

    override fun newHighlighter(): HighlightBenchHighlighter =
        HighlightBenchHighlighter { language, source, _, collectSpans ->
            val supported = OpenCodeSemanticFallbackHighlighter.supportsLanguage(language)
            val spans = if (supported) OpenCodeSemanticFallbackHighlighter.highlight(language, source).spans else emptyList()
            HighlightBenchOutput(
                outcome =
                    when {
                        !supported -> "Unsupported"
                        spans.isEmpty() -> "NoSpans"
                        else -> "Highlighted"
                    },
                spanCount = spans.size,
                highlighted = supported,
                spans = if (collectSpans) spans.map { HighlightBenchSpan(it.start, it.end, it.role.name) } else null,
            )
        }
}
