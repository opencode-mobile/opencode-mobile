package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchCatalog
import dev.opencode.mobile.highlight.bench.HighlightBenchDataKind
import dev.opencode.mobile.highlight.bench.HighlightBenchDataSet
import dev.opencode.mobile.highlight.bench.HighlightBenchSizeClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LabScreenModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun dataSetsFilterByLanguageSizeAndKindAndMeasureCharacters() =
        runTest(dispatcher) {
            val pasted = HighlightBenchDataSet.custom("t", "Pasted kotlin", "kotlin", KOTLIN_SOURCE)
            val model = LabDataSetsViewModel(smallStore(listOf(pasted)), labEngines(dispatcher))
            // One 2K data set per catalog language, plus the pasted one.
            assertEquals(HighlightBenchCatalog.languages.size + 1, model.state.value.rows.size)

            model.selectLanguage("kotlin")
            assertEquals(
                listOf("typical/kotlin/2k", "custom/t"),
                model.state.value.rows
                    .map { it.id },
            )

            model.selectKind(HighlightBenchDataKind.Custom)
            assertEquals(
                listOf("custom/t"),
                model.state.value.rows
                    .map { it.id },
            )

            model.selectKind(null)
            model.selectLanguage(null)
            model.selectSizeClass(HighlightBenchSizeClass.Huge)
            assertTrue(
                model.state.value.rows
                    .isEmpty(),
            )

            model.selectSizeClass(null)
            advanceUntilIdle()
            val rows = model.state.value.rows
            assertTrue(rows.all { it.characters != null })
            assertEquals(KOTLIN_SOURCE.length, rows.single { it.id == "custom/t" }.characters)
            assertEquals("kotlin · 2K · ${LabFormat.count(KOTLIN_SOURCE.length)} chars", rows.single { it.id == "custom/t" }.subtitle)
        }

    @Test
    fun pasteOpensACustomDataSetInThePickedLanguage() {
        val store = smallStore()
        val model = LabPasteViewModel(store)
        assertNull(model.open())

        model.setText(KOTLIN_SOURCE)
        model.selectLanguage("kotlin")
        val id = requireNotNull(model.open())

        val dataSet = requireNotNull(store.find(id))
        assertEquals("kotlin", dataSet.language)
        assertEquals(HighlightBenchDataKind.Custom, dataSet.kind)
        assertEquals(KOTLIN_SOURCE, dataSet.generate())
    }

    @Test
    fun benchmarkKeepsEngineOrderAndNeedsDataSetsWhenSelected() =
        runTest(dispatcher) {
            val model = benchmark(smallStore(), LabReportStore(createTempDirectory()))
            assertEquals(listOf("alpha", "beta"), model.state.value.engineOrder)
            assertEquals("1. Alpha (baseline)", model.state.value.engineLabel(LabEngineOption("alpha", "Alpha")))

            model.toggleEngine("alpha")
            model.toggleEngine("alpha")
            assertEquals(listOf("beta", "alpha"), model.state.value.engineOrder)
            assertEquals("2. Alpha", model.state.value.engineLabel(LabEngineOption("alpha", "Alpha")))

            model.selectScope(LabBenchmarkScope.Selected)
            assertFalse(model.state.value.canRun)
            model.toggleDataSet("typical/kotlin/2k")
            assertTrue(model.state.value.canRun)
            model.toggleEngine("alpha")
            model.toggleEngine("beta")
            assertFalse(model.state.value.canRun)
        }

    @Test
    fun benchmarkRunsPickedDataSetsComparesAndSavesTheReport() =
        runTest(dispatcher) {
            val directory = createTempDirectory()
            val reports = LabReportStore(directory, now = { 0L })
            val model = benchmark(smallStore(), reports)
            model.selectScope(LabBenchmarkScope.Selected)
            model.toggleDataSet("typical/kotlin/2k")

            model.run()
            assertTrue(model.state.value.running)
            advanceUntilIdle()

            val state = model.state.value
            assertFalse(state.running)
            assertNull(state.error)
            // One throughput case, one row per engine.
            assertEquals(
                2,
                state.tables
                    .single()
                    .rows.size,
            )
            assertEquals("Alpha", state.baselineName)
            assertEquals("Beta", state.comparisons.single().candidate)
            assertEquals("Throughput", state.comparisons.single().section)
            val report = requireNotNull(state.report)
            assertFalse(state.reportFromStorage)
            assertTrue(report.markdown.contains("Highlighting Lab run: selected data sets (Alpha, Beta)"))
            assertTrue(report.markdown.contains("Device: test"))
            assertEquals(report.json, reports.latest()?.json)

            // A new screen shows the saved report.
            val reopened = benchmark(smallStore(), reports)
            advanceUntilIdle()
            assertTrue(reopened.state.value.reportFromStorage)
            assertEquals(
                report.markdown,
                reopened.state.value.report
                    ?.markdown,
            )
            // Parsed by the Screen Model, so the screen does no Markdown work on the main thread.
            assertTrue(requireNotNull(reopened.state.value.storedReportDocument).blocks.isNotEmpty())
        }

    @Test
    fun benchmarkStopKeepsThePartialReport() =
        runTest(dispatcher) {
            val model = benchmark(smallStore(), LabReportStore(createTempDirectory()))
            model.selectScope(LabBenchmarkScope.Selected)
            model.toggleDataSet("typical/kotlin/2k")
            model.toggleDataSet("typical/json/2k")
            model.run()
            model.stop()
            assertTrue(model.state.value.stopping)
            advanceUntilIdle()

            val state = model.state.value
            assertFalse(state.running)
            // Stopped before the first case: every row is there, skipped.
            assertEquals(4, state.tables.sumOf { it.rows.size })
            assertTrue(requireNotNull(state.report).json.contains("Skipped"))
        }

    private fun benchmark(
        store: LabDataSetStore,
        reports: LabReportStore,
    ) = LabBenchmarkViewModel(store, labEngines(dispatcher), reports, LabDeviceInfo(listOf("Device: test")))

    private fun createTempDirectory() =
        kotlin.io.path
            .createTempDirectory("lab-test")
            .toFile()
            .also { it.deleteOnExit() }
}
