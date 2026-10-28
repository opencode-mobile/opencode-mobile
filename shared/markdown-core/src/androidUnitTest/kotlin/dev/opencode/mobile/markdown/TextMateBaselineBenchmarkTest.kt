package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.bench.HighlightBenchCaseStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchHeadless
import dev.opencode.mobile.highlight.bench.HighlightBenchRun
import dev.opencode.mobile.highlight.bench.HighlightBenchSection
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import dev.opencode.mobile.highlight.bench.HighlightBenchThreadGuard
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
 * (seconds), `HIGHLIGHT_BASELINE=basic` (javascript and the 17 Basic languages at quick depth), or
 * `HIGHLIGHT_BASELINE=full` (every language; adds 128K throughput, 1 MB files, worst cases; minutes).
 * Any Lab `labRun` value works too (see `HighlightBenchHeadless`), for example
 * `HIGHLIGHT_BASELINE=quick:engines=textmate,highlight-core,semantic-fallback`; without `engines=` only TextMate runs.
 * Report: `shared/markdown-core/build/reports/highlight-baseline/engine.md`, summary `engine.json`.
 */
class TextMateBaselineBenchmarkTest {
    @Test
    fun baseline() {
        val raw = System.getenv("HIGHLIGHT_BASELINE")?.trim()
        assumeTrue("Set HIGHLIGHT_BASELINE=quick or full to run", !raw.isNullOrEmpty())
        val value = if (raw == "1") "full" else raw!!
        val request = HighlightBenchHeadless.parse(if ("engines=" in value) value else "$value:engines=textmate")
        val engines =
            request.engines(
                listOf(
                    TextMateHighlightBenchEngine { OpenCodeBundledJoniMarkdownHighlighter.create(bundledTestResourceReader()) },
                    HighlightCoreHighlightBenchEngine,
                    SemanticFallbackHighlightBenchEngine,
                ),
            )
        val output = File("build/reports/highlight-baseline/engine.md")
        val selection = request.tier?.let { "${it.name.lowercase()} tier" } ?: request.selection
        val title = "${engines.joinToString { it.displayName }} engine baseline, $selection (JVM host, Joni)"
        val runtime = Runtime.getRuntime()
        val environment =
            listOf(
                "Host: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, " +
                    "${runtime.availableProcessors()} cores, max heap ${runtime.maxMemory() / (1024 * 1024)} MB",
                "JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
            )
        val run =
            HighlightBenchRun(
                engines = engines,
                cases = request.cases(),
                settings = request.settings,
                callGuard = HighlightBenchThreadGuard(stopAbandoned = ::forceStop),
            )
        println("HIGHLIGHT_BASELINE report: ${output.absolutePath}")
        val report =
            run.run { progress ->
                val result = progress.result
                if (result == null) {
                    println("HIGHLIGHT_BASELINE ${progress.index + 1}/${progress.total} ${progress.case.id} on ${progress.engineId}")
                } else {
                    println("HIGHLIGHT_BASELINE " + result.cells.joinToString(" | ", "| ", " |"))
                    output.parentFile.mkdirs()
                    output.writeText(progress.report.markdown(title, environment))
                }
            }
        File("build/reports/highlight-baseline/engine.json").writeText(report.json())
        // Skipped: a language the engine does not support yet (highlight-core before its Full milestone).
        val coldFailures =
            report.results(HighlightBenchSection.ColdStart).filter {
                it.status != HighlightBenchCaseStatus.Completed && it.status != HighlightBenchCaseStatus.Skipped
            }
        check(coldFailures.isEmpty()) { "Did not highlight: ${coldFailures.joinToString { "${it.case.title} on ${it.engineName} (${it.note ?: it.status})" }}" }
    }

    /** A thread that ignored its cancel flag is force-stopped so it cannot slow the cases after it. */
    @Suppress("DEPRECATION")
    private fun forceStop(thread: Thread): Boolean {
        thread.stop()
        thread.join(HighlightBenchSettings.standard.cancelGraceMs)
        return !thread.isAlive
    }
}
