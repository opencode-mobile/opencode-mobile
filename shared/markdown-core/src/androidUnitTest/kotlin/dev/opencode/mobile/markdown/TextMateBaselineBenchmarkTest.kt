package dev.opencode.mobile.markdown

import dev.opencode.mobile.markdown.BaselineReport.Companion.charsPerMs
import dev.opencode.mobile.markdown.BaselineReport.Companion.measure
import dev.opencode.mobile.markdown.BaselineReport.Companion.ms
import dev.opencode.mobile.markdown.BaselineReport.Companion.percentile
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test

/**
 * Baseline for #872: the current TextMate engine with the Android (Joni) backend on the JVM host.
 * Scheduler and File Viewer scenarios live in the app's `TextMateSchedulerBaselineTest`.
 *
 * Opt-in, run from `android/` (`--rerun` because Gradle does not see the variable as an input):
 * `HIGHLIGHT_BASELINE=quick ./gradlew :markdown-core:testDebugUnitTest --tests '*TextMateBaselineBenchmarkTest*' --rerun`
 * (seconds) or `HIGHLIGHT_BASELINE=full` (adds 128K throughput, 1 MB files, worst cases; minutes).
 * Report: `shared/markdown-core/build/reports/highlight-baseline/engine.md`.
 */
class TextMateBaselineBenchmarkTest {
    private val keys = AtomicLong()

    @Test
    fun baseline() {
        val tier = BaselineTier.fromEnvironment()
        assumeTrue("Set HIGHLIGHT_BASELINE=quick or full to run", tier != null)
        val report =
            BaselineReport(
                File("build/reports/highlight-baseline/engine.md"),
                "TextMate engine baseline, ${tier!!.name.lowercase()} tier (JVM host, Joni)",
            )
        coldStart(report)
        val engine = OpenCodeTextMateHighlightingEngine(highlighter())
        LANGUAGES.forEach { language -> engine.highlightNow(request(language, BaselineCorpus.code(language, 8_000))) }
        throughput(report, engine, if (tier == BaselineTier.Full) FULL_SIZES else QUICK_SIZES)
        fenceDistribution(report, engine)
        if (tier == BaselineTier.Full) {
            largeFiles(report)
            worstCases(report)
        }
    }

    /** First highlight per language on a fresh highlighter: grammar load, regex compile, cold JIT. */
    private fun coldStart(report: BaselineReport) {
        report.section("Cold start (fresh highlighter, first 2,000-character fence per language)")
        report.table("Language", "First call ms", "Second call ms", "Outcome")
        val engine = OpenCodeTextMateHighlightingEngine(highlighter())
        LANGUAGES.forEach { language ->
            val source = BaselineCorpus.code(language, 2_000)
            val (first, result) = measure { engine.highlightNow(request(language, source)) }
            check(result.outcome == TextMateParseOutcome.Highlighted) { "$language did not reach TextMate: ${result.outcome}" }
            val (second, _) = measure { engine.highlightNow(request(language, source)) }
            report.row(language, ms(first), ms(second), result.outcome.name)
        }
    }

    private fun throughput(
        report: BaselineReport,
        engine: OpenCodeTextMateHighlightingEngine,
        sizes: List<Pair<Int, Int>>,
    ) {
        report.section("Warm throughput (median of runs, characters per ms)")
        val headers = sizes.flatMap { (size, _) -> listOf("${size / 1_000}K ms", "${size / 1_000}K chars/ms") }
        report.table("Language", *headers.toTypedArray(), "Spans per 1K chars")
        LANGUAGES.forEach { language ->
            val cells = mutableListOf(language)
            var spansPerK = 0.0
            sizes.forEach { (size, runs) ->
                val source = BaselineCorpus.code(language, size)
                val samples =
                    (1..runs).map {
                        val (nanos, result) = measure { engine.highlightNow(request(language, source)) }
                        spansPerK = result.spans.size * 1_000.0 / source.length
                        nanos
                    }
                val median = percentile(samples, 50)
                cells += ms(median)
                cells += charsPerMs(source.length, median)
            }
            cells += "%.0f".format(spansPerK)
            report.row(*cells.toTypedArray())
        }
    }

    /** Fence sizes drawn from a fixed log-normal (median ~900 characters, clipped to 80..8,000). */
    private fun fenceDistribution(
        report: BaselineReport,
        engine: OpenCodeTextMateHighlightingEngine,
    ) {
        report.section("Chat fences (150 fences per language, sizes 80..8,000, median ~900 characters)")
        report.table("Language", "p50 ms", "p95 ms", "Max ms", "Fences over 16 ms", "2,000-char p50 ms", "2,000-char p95 ms")
        val random = Random(872)
        LANGUAGES.forEach { language ->
            val corpus = BaselineCorpus.code(language, 64_000)
            val fences =
                (1..150).map {
                    val size = exp(6.8 + random.nextDouble(-1.0, 1.0) * 1.3).toInt().coerceIn(80, 8_000)
                    val start = random.nextInt(0, corpus.length - size)
                    corpus.substring(corpus.lastIndexOf('\n', start) + 1).take(size)
                }
            val samples = fences.map { fence -> measure { engine.highlightNow(request(language, fence)) }.first }
            val fixed = BaselineCorpus.code(language, 2_000)
            val fixedSamples = (1..50).map { measure { engine.highlightNow(request(language, fixed)) }.first }
            report.row(
                language,
                ms(percentile(samples, 50)),
                ms(percentile(samples, 95)),
                ms(samples.max()),
                "${samples.count { it > 16_000_000 }} / ${samples.size}",
                ms(percentile(fixedSamples, 50)),
                ms(percentile(fixedSamples, 95)),
            )
        }
    }

    /** The whole-file cost if TextMate ran a 1 MB file directly, bypassing the scheduler. */
    private fun largeFiles(report: BaselineReport) {
        report.section("1 MB files, engine called directly (what a whole-file TextMate pass costs)")
        report.note(LIMIT_NOTE)
        report.table("Language", "Characters", "Lines", "ms", "chars/ms", "Outcome", "Spans", "After cancel")
        LANGUAGES.forEach { language ->
            val source = BaselineCorpus.code(language, ONE_MB)
            val run = timed(language, source)
            report.row(
                language,
                "%,d".format(source.length),
                "%,d".format(source.count { it == '\n' } + 1),
                run.msLabel(),
                if (run.timedOut) "-" else charsPerMs(source.length, run.nanos),
                run.outcome,
                "%,d".format(run.spans),
                run.afterCancel,
            )
        }
    }

    private fun worstCases(report: BaselineReport) {
        report.section("Worst-case inputs (fresh warmed highlighter each)")
        report.note(LIMIT_NOTE)
        report.table("Case", "Language", "Characters", "Longest line", "ms", "Outcome", "Spans", "After cancel")
        BaselineCorpus.worstCases().forEach { case ->
            val run = timed(case.language, case.source)
            report.row(
                case.name,
                case.language,
                "%,d".format(case.source.length),
                "%,d".format(case.source.split('\n').maxOf { it.length }),
                run.msLabel(),
                run.outcome,
                "%,d".format(run.spans),
                run.afterCancel,
            )
        }
    }

    private class TimedRun(
        val nanos: Long,
        val outcome: String,
        val spans: Int,
        val timedOut: Boolean,
        val afterCancel: String,
    ) {
        fun msLabel(): String = if (timedOut) "> ${ms(nanos)}" else ms(nanos)
    }

    /**
     * Runs on its own thread with a fresh engine. Past [LIMIT_MS] it sets the cancel flag; a thread
     * still running [CANCEL_GRACE_MS] later is force-stopped so it cannot slow the cases after it.
     */
    @Suppress("DEPRECATION")
    private fun timed(
        language: String,
        source: String,
    ): TimedRun {
        val engine = OpenCodeTextMateHighlightingEngine(highlighter())
        val warmup = if (language in LANGUAGES) BaselineCorpus.code(language, 8_000) else "const a = 1;\n"
        engine.highlightNow(request(language, warmup))
        val cancel = AtomicBoolean(false)
        val result = AtomicReference<HighlightResult?>()
        val finishedAt = AtomicLong()
        val done = CountDownLatch(1)
        val startedAt = System.nanoTime()
        val thread =
            Thread({
                try {
                    result.set(engine.highlightNow(request(language, source), shouldCancel = { cancel.get() }))
                } catch (ignored: Throwable) {
                    // ThreadDeath from a force stop, or an engine failure; both leave no result.
                } finally {
                    finishedAt.set(System.nanoTime())
                    done.countDown()
                }
            }, "highlight-baseline").apply {
                isDaemon = true
                start()
            }
        if (done.await(LIMIT_MS, TimeUnit.MILLISECONDS)) {
            val finished = result.get()
            return TimedRun(
                nanos = finishedAt.get() - startedAt,
                outcome = finished?.outcome?.name ?: "threw",
                spans = finished?.spans?.size ?: 0,
                timedOut = false,
                afterCancel = "-",
            )
        }
        val cancelledAt = System.nanoTime()
        cancel.set(true)
        if (done.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)) {
            return TimedRun(
                nanos = LIMIT_MS * 1_000_000,
                outcome = result.get()?.outcome?.name ?: "threw",
                spans = 0,
                timedOut = true,
                afterCancel = "stopped in ${ms(finishedAt.get() - cancelledAt)} ms",
            )
        }
        thread.stop()
        done.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
        return TimedRun(
            nanos = LIMIT_MS * 1_000_000,
            outcome = "Ignored cancel",
            spans = 0,
            timedOut = true,
            afterCancel = "ignored, force-stopped",
        )
    }

    private fun request(
        language: String,
        source: String,
    ) = HighlightRequest(
        key = CodeBlockKey("baseline:${keys.incrementAndGet()}"),
        revision = HighlightRevision(1),
        language = language,
        source = source,
        intent = HighlightIntent.InteractiveFinal,
        policyEpoch = 0,
    )

    private companion object {
        const val ONE_MB = 1_000_000
        const val LIMIT_MS = 10_000L
        const val CANCEL_GRACE_MS = 2_000L
        const val LIMIT_NOTE =
            "Each run stops at 10 s (the whole-file budget is 500 ms). \"After cancel\" says whether the " +
                "engine honored the cancel flag within 2 s; a run that ignored it was force-stopped."
        val LANGUAGES = listOf("typescript", "kotlin", "swift", "python", "json", "diff")
        val QUICK_SIZES = listOf(2_000 to 30, 16_000 to 5)
        val FULL_SIZES = QUICK_SIZES + (128_000 to 3)

        fun highlighter(): OpenCodeSyntaxHighlighter = OpenCodeBundledJoniMarkdownHighlighter.create(bundledTestResourceReader())
    }
}
