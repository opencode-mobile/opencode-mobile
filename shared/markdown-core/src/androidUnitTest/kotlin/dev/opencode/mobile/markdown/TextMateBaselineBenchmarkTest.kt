package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCaseStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchRun
import dev.opencode.mobile.highlight.bench.HighlightBenchSection
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import dev.opencode.mobile.highlight.bench.HighlightBenchThreadGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchTier
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Baseline for #872: the current TextMate engine with the Android (Joni) backend on the JVM host,
 * run through the shared `highlight-bench` runner the Highlighting Lab uses on devices.
 * Scheduler and File Viewer scenarios live in the app's `TextMateSchedulerBaselineTest`.
 *
 * Opt-in, run from `android/` (`--rerun` because Gradle does not see the variable as an input):
 * `HIGHLIGHT_BASELINE=quick ./gradlew :markdown-core:testDebugUnitTest --tests '*TextMateBaselineBenchmarkTest*' --rerun`
 * (seconds) or `HIGHLIGHT_BASELINE=full` (adds 128K throughput, 1 MB files, worst cases; minutes).
 * Report: `shared/markdown-core/build/reports/highlight-baseline/engine.md`.
 */
class TextMateBaselineBenchmarkTest {
    @Test
    fun baseline() {
        val tier =
            when (System.getenv("HIGHLIGHT_BASELINE")?.lowercase()) {
                "quick" -> HighlightBenchTier.Quick
                "full", "1" -> HighlightBenchTier.Full
                else -> null
            }
        assumeTrue("Set HIGHLIGHT_BASELINE=quick or full to run", tier != null)
        val output = File("build/reports/highlight-baseline/engine.md")
        val title = "TextMate engine baseline, ${tier!!.name.lowercase()} tier (JVM host, Joni)"
        val runtime = Runtime.getRuntime()
        val environment =
            listOf(
                "Host: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, " +
                    "${runtime.availableProcessors()} cores, max heap ${runtime.maxMemory() / (1024 * 1024)} MB",
                "JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
            )
        val run =
            HighlightBenchRun(
                engines = listOf(TextMateHighlightBenchEngine { OpenCodeBundledJoniMarkdownHighlighter.create(bundledTestResourceReader()) }),
                tier = tier,
                settings = HighlightBenchSettings.standard,
                callGuard = HighlightBenchThreadGuard(stopAbandoned = ::forceStop),
            )
        println("HIGHLIGHT_BASELINE report: ${output.absolutePath}")
        val report =
            run.run { progress ->
                val result = progress.result
                if (result == null) {
                    println("HIGHLIGHT_BASELINE ${progress.index + 1}/${progress.total} ${progress.case.id}")
                } else {
                    println("HIGHLIGHT_BASELINE " + result.cells.joinToString(" | ", "| ", " |"))
                    output.parentFile.mkdirs()
                    output.writeText(progress.report.markdown(title, environment))
                }
            }
        val coldFailures = report.results(HighlightBenchSection.ColdStart).filter { it.status != HighlightBenchCaseStatus.Completed }
        check(coldFailures.isEmpty()) { "Did not reach TextMate: ${coldFailures.joinToString { "${it.case.title} (${it.note ?: it.status})" }}" }
    }

    /** A thread that ignored its cancel flag is force-stopped so it cannot slow the cases after it. */
    @Suppress("DEPRECATION")
    private fun forceStop(thread: Thread): Boolean {
        thread.stop()
        thread.join(HighlightBenchSettings.standard.cancelGraceMs)
        return !thread.isAlive
    }
}
