package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan

/**
 * The current TextMate engine behind the #872 Highlighting Lab's benchmark seam. Each
 * [newHighlighter] is a fresh [OpenCodeTextMateHighlightingEngine] over a fresh highlighter, so
 * cold-start cases pay grammar loading and regex compilation again.
 */
class TextMateHighlightBenchEngine(
    private val createHighlighter: () -> OpenCodeCodeHighlighter,
) : HighlightBenchEngine {
    /** The bundled platform engine: Joni on Android (after app start-up), libonig on iOS. */
    constructor() : this(::createOpenCodePlatformMarkdownHighlighter)

    override val id: String = "textmate"

    override val displayName: String = "TextMate"

    override fun newHighlighter(): HighlightBenchHighlighter {
        val engine = OpenCodeTextMateHighlightingEngine(createHighlighter())
        var calls = 0L
        return HighlightBenchHighlighter { language, source, cancel, collectSpans ->
            calls += 1
            val request =
                HighlightRequest(
                    key = CodeBlockKey("bench:$calls"),
                    revision = HighlightRevision(1),
                    language = language,
                    source = source,
                    intent = HighlightIntent.InteractiveFinal,
                    policyEpoch = 0,
                )
            val result = engine.highlightNow(request, shouldCancel = { cancel.isCancelled })
            HighlightBenchOutput(
                outcome = result.outcome.name,
                spanCount = result.spans.size,
                highlighted = result.outcome == TextMateParseOutcome.Highlighted,
                spans = if (collectSpans) result.spans.map { HighlightBenchSpan(it.start, it.end, it.role.name) } else null,
            )
        }
    }
}
