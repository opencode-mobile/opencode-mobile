package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCatalog
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchInlineGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import dev.opencode.mobile.highlight.bench.HighlightBenchSizeClass
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan
import dev.opencode.mobile.highlight.bench.HighlightBenchSpanDiff
import dev.opencode.mobile.highlight.core.HighlightCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * #872: how often highlight-core gives a character the same role as TextMate (bundled Lab data
 * sets) and as Shiki (the committed Shiki fixtures), per language it supports. For review, not a
 * gate. Opt-in, from `android/`:
 * `HIGHLIGHT_AGREEMENT=1 ./gradlew :markdown-core:testDebugUnitTest --tests '*HighlightCoreAgreementTest*' --rerun`
 * Report: `shared/markdown-core/build/reports/highlight-baseline/agreement.md`.
 */
class HighlightCoreAgreementTest {
    @Test
    fun agreement() {
        assumeTrue("Set HIGHLIGHT_AGREEMENT=1 to run", !System.getenv("HIGHLIGHT_AGREEMENT").isNullOrEmpty())
        val textMate = TextMateHighlightBenchEngine { OpenCodeBundledJoniMarkdownHighlighter.create(bundledTestResourceReader()) }.newHighlighter()
        val core = HighlightCoreHighlightBenchEngine.newHighlighter()
        val settings = HighlightBenchSettings.standard

        fun call(
            highlighter: HighlightBenchHighlighter,
            language: String,
            source: String,
        ) = HighlightBenchProbe.highlight(highlighter, language, source, settings, HighlightBenchInlineGuard)

        val rows = mutableListOf<String>()
        val languages = HighlightBenchCatalog.languages.filter { HighlightCore.supports(it) }
        for (language in languages) {
            val inputs =
                listOf(HighlightBenchSizeClass.Small, HighlightBenchSizeClass.Medium, HighlightBenchSizeClass.Large).map { size ->
                    "typical ${size.label}" to HighlightBenchCatalog.filter(language, size, null).first().pieces()
                } + ("chat fences, each" to HighlightBenchCatalog.dataSet("fences/$language")!!.pieces())
            for ((label, pieces) in inputs) {
                // Only inputs TextMate actually highlighted; it gives up on some large ones.
                var characters = 0
                var differing = 0
                var ranges = 0
                val outcomes = sortedSetOf<String>()
                for (source in pieces) {
                    val baseline = call(textMate, language, source)
                    outcomes += baseline.outcome ?: baseline.status.name
                    if (!baseline.highlighted) continue
                    val comparison = HighlightBenchSpanDiff.compare(source.length, baseline.spans.orEmpty(), call(core, language, source).spans.orEmpty())
                    characters += source.length
                    differing += comparison.differingCharacters
                    ranges += comparison.differingRangeCount
                }
                val agreement = if (characters == 0) "n/a" else percent(1.0 - differing.toDouble() / characters)
                rows += "| $language | TextMate (${outcomes.joinToString()}) | $label | $characters | $agreement | $ranges |"
            }
        }
        val shiki = Json.parseToJsonElement(File("src/commonTest/resources/highlighter-goldens/shiki-semantic-tokens.json").readText())
        shiki.jsonObject["fixtures"]!!.jsonArray.map { it.jsonObject }.forEach { fixture ->
            val language = fixture["language"]!!.jsonPrimitive.content
            if (!HighlightCore.supports(language)) return@forEach
            val code = fixture["code"]!!.jsonPrimitive.content
            val expected =
                fixture["tokens"]!!.jsonArray.map { token ->
                    val fields = token.jsonObject
                    HighlightBenchSpan(fields["start"]!!.jsonPrimitive.int, fields["end"]!!.jsonPrimitive.int, fields["role"]!!.jsonPrimitive.content)
                }
            val comparison = HighlightBenchSpanDiff.compare(code.length, expected, call(core, language, code).spans.orEmpty())
            rows += "| $language | Shiki | ${fixture["id"]!!.jsonPrimitive.content} | ${code.length} | ${percent(comparison.agreement)} | ${comparison.differingRangeCount} |"
        }
        val report =
            buildString {
                appendLine("# highlight-core agreement (share of characters with the same role, plain included)")
                appendLine()
                appendLine("| Language | Against | Input | Characters compared | Agreement | Differing ranges |")
                appendLine("| --- | --- | --- | --- | --- | --- |")
                rows.forEach { appendLine(it) }
            }
        val output = File("build/reports/highlight-baseline/agreement.md")
        output.parentFile.mkdirs()
        output.writeText(report)
        println(report)
    }

    private fun percent(share: Double): String = "${(share * 1_000).toInt() / 10.0}%"
}
