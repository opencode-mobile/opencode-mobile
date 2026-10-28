package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCancelSignal
import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan
import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.highlight.core.HighlightStatus

/**
 * The #872 highlight-core library behind the Highlighting Lab's benchmark seam, for comparing it
 * with TextMate. Lab only: nothing else in the apps uses highlight-core yet. Languages without a
 * lexer come back as unsupported (and runs skip them), never as plain "highlighted" text.
 */
object HighlightCoreHighlightBenchEngine : HighlightBenchEngine {
    override val id: String = "highlight-core"

    override val displayName: String = "highlight-core"

    override fun supportsLanguage(language: String): Boolean = HighlightCore.supports(language)

    override fun newHighlighter(): HighlightBenchHighlighter =
        HighlightBenchHighlighter { language, source, cancel, collectSpans ->
            if (!HighlightCore.supports(language)) {
                HighlightBenchOutput("Unsupported", 0, highlighted = false, spans = if (collectSpans) emptyList() else null)
            } else {
                val result = HighlightCore.highlight(language, source, BenchCancellation(cancel), HighlightDeadline.never)
                val spans = result.spans
                HighlightBenchOutput(
                    outcome = result.status.name,
                    spanCount = spans.count,
                    highlighted = result.status == HighlightStatus.Complete,
                    spans = if (collectSpans) List(spans.count) { HighlightBenchSpan(spans.start(it), spans.end(it), spans.role(it).name) } else null,
                )
            }
        }

    /**
     * The benchmark's signal reads a clock on every check; the library checks once per line, so
     * only every 32nd check reaches the signal to keep the clock out of the timings.
     */
    private class BenchCancellation(
        private val signal: HighlightBenchCancelSignal,
    ) : HighlightCancellation {
        private var checks = 0
        private var cancelled = false

        override val isCancelled: Boolean
            get() {
                if (!cancelled && ++checks >= 32) {
                    checks = 0
                    cancelled = signal.isCancelled
                }
                return cancelled
            }
    }
}
