package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchEngine

/** The engines a Highlighting Lab can benchmark, in the order it lists them. */
object HighlightBenchEngines {
    /** The #872 highlight-core library, the only engine the apps ship. */
    fun available(): List<HighlightBenchEngine> = listOf(HighlightCoreHighlightBenchEngine)

    fun engine(id: String): HighlightBenchEngine? = available().firstOrNull { it.id == id }
}
