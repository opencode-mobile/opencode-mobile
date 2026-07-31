package dev.opencode.mobile.markdown

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class TextMateSoakTest {
    @Test
    fun mixedLanguageSoakWhenRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        val durationSeconds = arguments.getString(ARGUMENT_DURATION_SECONDS)?.toLongOrNull() ?: 0L
        assumeTrue("Set $ARGUMENT_DURATION_SECONDS to run the opt-in TextMate soak.", durationSeconds > 0L)

        val metrics = SoakMetricsSink()
        val highlighter = createHighlighter(metrics)
        val workload = mixedLanguageWorkload()

        repeat(WARM_UP_CYCLES) {
            parseCycle(workload, highlighter)
        }
        val retainedBaselinePssKb = stabilizedPssKb()
        var peakPssKb = retainedBaselinePssKb
        var cycles = 0
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + durationSeconds * 1_000L

        while (SystemClock.elapsedRealtime() < deadline) {
            parseCycle(workload, highlighter)
            cycles += 1
            peakPssKb = max(peakPssKb, Debug.getPss())
        }

        val retainedFinalPssKb = stabilizedPssKb()
        peakPssKb = max(peakPssKb, retainedFinalPssKb)
        val retainedGrowthKb = retainedFinalPssKb - retainedBaselinePssKb
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        println(
            "TEXTMATE_SOAK platform=android-joni duration-ms=$elapsedMs cycles=$cycles " +
                "warmup-cycles=$WARM_UP_CYCLES retained-baseline-pss-kb=$retainedBaselinePssKb " +
                "retained-final-pss-kb=$retainedFinalPssKb retained-growth-kb=$retainedGrowthKb " +
                "peak-pss-kb=$peakPssKb native-search-count=${metrics.nativeSearchCount} " +
                "native-search-max-ms=${metrics.nativeSearchMaximumNanoseconds / 1_000_000.0}",
        )

        assertTrue("The soak must complete at least one mixed-language cycle.", cycles > 0)
        assertTrue(
            "Retained PSS grew by $retainedGrowthKb KiB after warm-up.",
            retainedGrowthKb <= RETAINED_PSS_TOLERANCE_KB,
        )
    }

    @Test
    fun referenceFixtureColdAndWarmWhenRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set $ARGUMENT_BENCHMARK to run the opt-in TextMate reference benchmark.",
            arguments.getString(ARGUMENT_BENCHMARK)?.toBooleanStrictOrNull() == true,
        )

        val metrics = SoakMetricsSink()
        val fixture = longMarkdownHistory()
        val pssBeforeKb = stabilizedPssKb()
        val coldStartedAt = SystemClock.elapsedRealtimeNanos()
        val highlighter = createHighlighter(metrics)
        parseCycle(fixture, highlighter)
        val coldMs = (SystemClock.elapsedRealtimeNanos() - coldStartedAt) / 1_000_000.0
        val warmSamplesMs =
            List(WARM_SAMPLE_COUNT) {
                val startedAt = SystemClock.elapsedRealtimeNanos()
                parseCycle(fixture, highlighter)
                (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
            }
        val retainedPssKb = stabilizedPssKb()

        assertEquals(20, fixture.size)
        println(
            "TEXTMATE_BENCHMARK platform=android-joni cold-ms=$coldMs " +
                "warm-ms=${warmSamplesMs.joinToString(",")} warm-samples=$WARM_SAMPLE_COUNT " +
                "pss-before-kb=$pssBeforeKb retained-pss-kb=$retainedPssKb " +
                "native-search-count=${metrics.nativeSearchCount} " +
                "native-search-max-ms=${metrics.nativeSearchMaximumNanoseconds / 1_000_000.0} " +
                "messages=20 code-lines=3000",
        )
    }

    private fun createHighlighter(metrics: OpenCodeTextMateMetricsSink): OpenCodeSyntaxHighlighter {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return OpenCodeBundledJoniMarkdownHighlighter.create(
            resourceReader =
                OpenCodeTextMateResourceReader { resourcePath ->
                    runCatching {
                        context.assets
                            .open(resourcePath)
                            .bufferedReader()
                            .use { reader -> reader.readText() }
                    }.getOrNull()
                },
            metricsSink = metrics,
        )
    }

    private fun parseCycle(
        workload: List<String>,
        highlighter: OpenCodeSyntaxHighlighter,
    ) {
        workload.forEach { markdown ->
            val document = OpenCodeMarkdownParser.parse(markdown, highlighter)
            assertTrue(document.blocks.any { block -> block is MarkdownBlock.CodeBlock })
        }
    }

    private fun stabilizedPssKb(): Long {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(100)
        }
        return Debug.getPss()
    }

    private class SoakMetricsSink : OpenCodeTextMateMetricsSink {
        var nativeSearchCount: Long = 0
            private set
        var nativeSearchMaximumNanoseconds: Long = 0
            private set

        override fun record(event: TextMateMetricEvent) {
            if (event.stage == TextMateMetricStage.NativeRegexSearch) {
                nativeSearchCount += 1
                nativeSearchMaximumNanoseconds =
                    max(nativeSearchMaximumNanoseconds, event.durationNanoseconds)
            }
        }
    }

    private fun mixedLanguageWorkload(): List<String> =
        listOf(
            markdown("typescript") { index -> "const item$index = response$index?.value ?? 'soak';" },
            markdown("kotlin") { index -> "val item$index = response$index?.value ?: \"soak\"" },
            markdown("swift") { index -> "let item$index = response$index?.value ?? \"soak\"" },
            markdown("python") { index -> "item_$index = response_$index.value if response_$index else 'soak'" },
            markdown("json") { index -> "\"item$index\": { \"value\": $index, \"enabled\": true }," },
            markdown("shellscript") { index -> "item_$index=\"\${response_$index:-soak}\"" },
        )

    private fun longMarkdownHistory(): List<String> =
        (1..20).map { messageIndex ->
            buildString {
                append("## Response $messageIndex\n\n```typescript\n")
                repeat(150) { lineIndex ->
                    append("const item$lineIndex = response$lineIndex?.value ?? 'markdown benchmark';\n")
                }
                append("```\n")
            }
        }

    private fun markdown(
        language: String,
        line: (Int) -> String,
    ): String =
        buildString {
            append("## TextMate soak\n\n```")
            append(language)
            append('\n')
            repeat(LINES_PER_LANGUAGE) { index ->
                append(line(index))
                append('\n')
            }
            append("```\n")
        }

    companion object {
        private const val ARGUMENT_DURATION_SECONDS = "textmateSoakSeconds"
        private const val ARGUMENT_BENCHMARK = "textmateBenchmark"
        private const val WARM_UP_CYCLES = 5
        private const val WARM_SAMPLE_COUNT = 5
        private const val LINES_PER_LANGUAGE = 40
        private const val RETAINED_PSS_TOLERANCE_KB = 8L * 1_024L
    }
}
