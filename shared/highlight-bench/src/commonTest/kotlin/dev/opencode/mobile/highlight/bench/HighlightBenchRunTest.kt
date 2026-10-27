package dev.opencode.mobile.highlight.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HighlightBenchRunTest {
    @Test
    fun quickTierWithAFastEngineCompletesEveryCaseAndReportsProgress() {
        val engine = FakeEngine { _, source, _ -> highlighted(source) }
        val run = HighlightBenchRun(listOf(engine), HighlightBenchTier.Quick, settings(limitMs = 10_000), HighlightBenchInlineGuard)
        val progress = mutableListOf<HighlightBenchProgress>()

        val report = run.run { progress += it }

        assertEquals(24, report.results.size)
        assertTrue(report.isComplete)
        assertTrue(report.results.all { it.status == HighlightBenchCaseStatus.Completed })
        assertEquals(
            listOf(HighlightBenchSection.ColdStart, HighlightBenchSection.Throughput, HighlightBenchSection.ChatFences),
            report.sections,
        )
        assertEquals(48, progress.size)
        assertNull(progress[0].result)
        assertEquals(0, progress[0].report.results.size)
        assertEquals(report.results[0], progress[1].result)
        assertEquals(1, progress[1].report.results.size)
        // One fresh instance for cold start, one shared warm instance for the rest.
        assertEquals(2, engine.instances)
        report.sections.forEach { section ->
            report.results(section).forEach { assertEquals(report.headers(section).size, it.cells.size, it.case.id) }
        }
        assertEquals(
            listOf("Data set", "Characters", "Runs", "Median ms", "chars/ms", "Spans per 1K chars"),
            report.headers(HighlightBenchSection.Throughput),
        )
        val fences = report.results(HighlightBenchSection.ChatFences).first().measurement as HighlightBenchMeasurement.ChatFences
        assertEquals(150, fences.fenceCount)
        assertEquals(0, fences.overFrameBudget)
        val throughput = report.results(HighlightBenchSection.Throughput).map { it.case.dataSet.id to (it.measurement as HighlightBenchMeasurement.Throughput).runs }
        assertEquals(listOf("typical/typescript/2k" to 30, "typical/typescript/16k" to 5), throughput.take(2))

        val markdown = report.markdown("Fake baseline", listOf("Host: test"))
        assertTrue(markdown.startsWith("# Fake baseline\n\n- Host: test\n"))
        assertTrue("## Cold start (fresh highlighter, first 2K fence per language)" in markdown)
        assertFalse("## Comparison" in markdown)
        assertTrue("| Language | p50 ms | p95 ms | Max ms | Fences over 16 ms | 2,000-char p50 ms | 2,000-char p95 ms |" in markdown)
        assertTrue("| typescript |" in markdown)
    }

    @Test
    fun aCallThatStopsWhenCancelledIsOverLimit() {
        val report = runCustom(honorsCancel(), settings(limitMs = 30, graceMs = 500))
        val result = report.results.single()

        assertEquals(HighlightBenchCaseStatus.OverLimit, result.status)
        val call = assertNotNull(result.failedCall)
        assertEquals(HighlightBenchCallStatus.CancelHonored, call.status)
        assertEquals("Cancelled", call.outcome)
        assertTrue(call.elapsedMs >= 30.0)
        assertEquals("> 30.0", result.cells[4])
        assertTrue(result.cells[7].startsWith("stopped in "), result.cells[7])
    }

    @Test
    fun aCallThatReturnsAfterTheGraceIgnoredCancel() {
        val report = runCustom(ignoresCancel(150.milliseconds), settings(limitMs = 20, graceMs = 20))
        val result = report.results.single()

        assertEquals(HighlightBenchCaseStatus.IgnoredCancel, result.status)
        val call = assertNotNull(result.failedCall)
        assertEquals(HighlightBenchCallStatus.CancelIgnored, call.status)
        assertTrue(call.status.ignoredCancel)
        assertEquals("Highlighted", call.outcome)
        assertTrue(result.cells[7].startsWith("ignored, returned "), result.cells[7])
    }

    @Test
    fun aThrowingEngineFailsOnlyItsCase() {
        val report =
            HighlightBenchRun(listOf(throws()), listOf(custom("bad"), plain("good")), settings(), HighlightBenchInlineGuard)
                .run {}

        val (bad, good) = report.results
        assertEquals(HighlightBenchCaseStatus.Failed, bad.status)
        assertEquals(HighlightBenchCallStatus.Threw, bad.failedCall?.status)
        assertTrue(bad.note!!.contains("boom"), bad.note)
        assertEquals("threw", bad.cells[5])
        assertEquals(HighlightBenchCaseStatus.Completed, good.status)
    }

    @Test
    fun aFailingEngineFactoryFailsTheCaseInsteadOfTheRun() {
        val engine =
            object : HighlightBenchEngine {
                override val id = "broken"
                override val displayName = "Broken"

                override fun newHighlighter(): HighlightBenchHighlighter = error("no grammars")
            }
        val report = HighlightBenchRun(listOf(engine), listOf(custom()), settings(), HighlightBenchInlineGuard).run {}

        val result = report.results.single()
        assertEquals(HighlightBenchCaseStatus.Failed, result.status)
        assertNull(result.measurement)
        assertTrue(result.note!!.contains("no grammars"), result.note)
        assertEquals(8, result.cells.size)
    }

    @Test
    fun aColdStartThatDoesNotHighlightFails() {
        val engine = FakeEngine { _, _, _ -> HighlightBenchOutput("Fallback", 0, highlighted = false) }
        val cold = HighlightBenchPlan.cases(HighlightBenchTier.Quick).first { it.section == HighlightBenchSection.ColdStart }
        val result = HighlightBenchRun(listOf(engine), listOf(cold), settings(limitMs = 10_000), HighlightBenchInlineGuard).run {}.results.single()

        assertEquals(HighlightBenchCaseStatus.Failed, result.status)
        assertEquals("Did not highlight: Fallback", result.note)
        assertEquals("Fallback", result.cells[3])
    }

    @Test
    fun anAbandonedCallSkipsLaterIsolatedCasesWhileItRuns() {
        val report = HighlightBenchRun(listOf(fast), listOf(custom("first"), custom("second")), settings(), AbandoningGuard).run {}

        val (first, second) = report.results
        assertEquals(HighlightBenchCaseStatus.IgnoredCancel, first.status)
        assertEquals(HighlightBenchCallStatus.Abandoned, first.failedCall?.status)
        assertEquals("Ignored cancel", first.cells[5])
        assertEquals("ignored, left running", first.cells[7])
        assertEquals(HighlightBenchCaseStatus.Skipped, second.status)
        assertTrue(second.note!!.contains("1 abandoned call"), second.note)
        assertFalse(second.slowedByStuckCall)
    }

    @Test
    fun runAnywayMarksCasesAfterAnAbandonedCallAsSlowed() {
        val report =
            HighlightBenchRun(listOf(fast), listOf(custom("first"), custom("second")), settings(policy = HighlightBenchStuckPolicy.RunAnyway), AbandoningGuard)
                .run {}

        val second = report.results[1]
        assertEquals(HighlightBenchCallStatus.Abandoned, second.failedCall?.status)
        assertTrue(second.slowedByStuckCall)
        assertEquals("second †", second.cells[0])
        assertTrue("† An abandoned call was still running" in report.markdown("t", emptyList()))
    }

    @Test
    fun aForceStoppedCallDoesNotSkipLaterCases() {
        val guard =
            object : HighlightBenchCallGuard {
                override fun execute(task: HighlightBenchTask) = HighlightBenchGuardExit.ForceStopped
            }
        val report = HighlightBenchRun(listOf(fast), listOf(custom("first"), custom("second")), settings(), guard).run {}

        assertEquals("ignored, force-stopped", report.results[0].cells[7])
        assertEquals(HighlightBenchCallStatus.ForceStopped, report.results[1].failedCall?.status)
        assertFalse(report.results[1].slowedByStuckCall)
    }

    @Test
    fun theThreadGuardGivesUpOnAHungCallAndTheRunGoesOn() {
        val release = Latch()
        val engine = hangsUntil(release)
        val cases = listOf(plain("quick"), custom("hung"), custom("after"))
        try {
            val report = HighlightBenchRun(listOf(engine), cases, settings(limitMs = 20, graceMs = 20), platformThreadGuard()).run {}

            val (quick, hung, after) = report.results
            assertEquals(HighlightBenchCaseStatus.Completed, quick.status)
            assertEquals(HighlightBenchCaseStatus.IgnoredCancel, hung.status)
            assertEquals(HighlightBenchCallStatus.Abandoned, hung.failedCall?.status)
            assertEquals(HighlightBenchCaseStatus.Skipped, after.status)
        } finally {
            release.isOpen = true
        }
    }

    @Test
    fun stopSkipsTheRemainingCases() {
        lateinit var run: HighlightBenchRun
        val engine =
            FakeEngine { _, source, _ ->
                if (source.startsWith(MARKER)) run.stop()
                highlighted(source)
            }
        run = HighlightBenchRun(listOf(engine), listOf(custom("first"), custom("second")), settings(), HighlightBenchInlineGuard)

        val report = run.run {}

        assertTrue(run.isStopped)
        assertTrue(report.results.all { it.status == HighlightBenchCaseStatus.Skipped && it.note == "Run stopped" })
    }

    @Test
    fun aSlowThroughputRunEndsItsCase() {
        val engine =
            FakeEngine { _, source, cancel ->
                if (source.length > 10_000) {
                    while (!cancel.isCancelled) Unit
                }
                highlighted(source)
            }
        val cases = HighlightBenchPlan.cases(listOf(HighlightBenchCatalog.dataSet("typical/kotlin/16k")!!))
        val report = HighlightBenchRun(listOf(engine), cases, settings(limitMs = 30, graceMs = 500), HighlightBenchInlineGuard).run {}

        val result = report.results.single()
        assertEquals(HighlightBenchCaseStatus.OverLimit, result.status)
        assertNull(result.measurement)
        assertEquals(listOf("kotlin 16K", "Highlighted (> 30.0 ms)"), result.cells.take(2))
    }

    @Test
    fun twoEnginesRunEachCaseInTurnAndPairTheirResults() {
        val slow = FakeEngine("slow") { _, source, _ -> spin(4.milliseconds).let { highlighted(source) } }
        val quick = FakeEngine("quick") { _, source, _ -> highlighted(source) }
        val cases = HighlightBenchPlan.cases(listOf(HighlightBenchCatalog.dataSet("typical/json/16k")!!)) + custom("pasted")
        val steps = mutableListOf<String>()

        val report = HighlightBenchRun(listOf(slow, quick), cases, settings(limitMs = 10_000), HighlightBenchInlineGuard).run { progress ->
            if (progress.result == null) steps += "${progress.case.id}@${progress.engineId}"
        }

        assertEquals(listOf("throughput/typical/json/16k@slow", "throughput/typical/json/16k@quick", "timed/custom/pasted@slow", "timed/custom/pasted@quick"), steps)
        assertEquals(listOf("slow", "quick"), report.engines.map { it.id })
        assertEquals("Engine", report.headers(HighlightBenchSection.Throughput)[1])
        assertEquals("Quick", report.result("throughput/typical/json/16k", "quick")!!.cells[1])
        val comparisons = report.comparisons()
        assertEquals(2, comparisons.size)
        val throughput = comparisons.first()
        assertEquals("slow", throughput.baseline.engineId)
        assertEquals("quick", throughput.candidate.engineId)
        assertTrue(throughput.timeRatio!! < 0.5, "ratio ${throughput.timeRatio}")
        val markdown = report.markdown("Two engines", emptyList())
        assertTrue("## Comparison" in markdown)
        assertTrue("| Throughput: json 16K | median ms | Slow |" in markdown, markdown)
    }

    @Test
    fun theProbeReturnsSpansAndTiming() {
        val highlighter = fast.newHighlighter()
        val call = HighlightBenchProbe.highlight(highlighter, "kotlin", "x".repeat(50), settings(), platformThreadGuard())

        assertEquals(HighlightBenchCallStatus.Completed, call.status)
        assertEquals(5, call.spans?.size)
        assertEquals(HighlightBenchSpan(10, 15, "Keyword"), call.spans?.get(1))
    }

    @Test
    fun aRunExecutesOnce() {
        val run = HighlightBenchRun(listOf(fast), emptyList(), settings(), HighlightBenchInlineGuard)
        run.run {}
        assertFailsWith<IllegalStateException> { run.run {} }
    }

    private fun runCustom(
        engine: HighlightBenchEngine,
        settings: HighlightBenchSettings,
    ): HighlightBenchReport = HighlightBenchRun(listOf(engine), listOf(custom()), settings, HighlightBenchInlineGuard).run {}

    /** Gives up without ever running the task, so the call counts as still running. */
    private object AbandoningGuard : HighlightBenchCallGuard {
        override fun execute(task: HighlightBenchTask) = HighlightBenchGuardExit.Abandoned
    }
}
