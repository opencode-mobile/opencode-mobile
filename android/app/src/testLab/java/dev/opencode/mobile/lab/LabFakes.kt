package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchCatalog
import dev.opencode.mobile.highlight.bench.HighlightBenchDataSet
import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchInlineGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchSizeClass
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan
import kotlinx.coroutines.CoroutineDispatcher

/** Colors every occurrence of [words] as keywords, instantly. */
internal class WordEngine(
    override val id: String,
    private val words: List<String>,
) : HighlightBenchEngine {
    override val displayName: String = id.replaceFirstChar { it.uppercase() }

    var instances = 0
        private set

    override fun newHighlighter(): HighlightBenchHighlighter {
        instances += 1
        return HighlightBenchHighlighter { _, source, _, collectSpans ->
            val spans =
                words
                    .flatMap { word ->
                        Regex("\\b${Regex.escape(word)}\\b").findAll(source).map {
                            HighlightBenchSpan(
                                it.range.first,
                                it.range.last + 1,
                                "Keyword",
                            )
                        }
                    }.sortedBy { it.start }
            HighlightBenchOutput("Highlighted", spans.size, highlighted = true, spans = if (collectSpans) spans else null)
        }
    }
}

internal val baselineEngine = WordEngine("alpha", listOf("fun"))
internal val candidateEngine = WordEngine("beta", listOf("fun", "val"))

internal fun labEngines(
    dispatcher: CoroutineDispatcher,
    engines: List<HighlightBenchEngine> = listOf(baselineEngine, candidateEngine),
): LabEngines = LabEngines(engines, { HighlightBenchInlineGuard }, dispatcher)

/** The small typical-code data sets only, so tests do not generate megabytes. */
internal fun smallStore(extra: List<HighlightBenchDataSet> = emptyList()): LabDataSetStore =
    LabDataSetStore(HighlightBenchCatalog.dataSets.filter { it.sizeClass == HighlightBenchSizeClass.Small } + extra)

internal const val KOTLIN_SOURCE = "fun main() {\n\tval x = 1\n\tfun inner() = x\n}\n"
