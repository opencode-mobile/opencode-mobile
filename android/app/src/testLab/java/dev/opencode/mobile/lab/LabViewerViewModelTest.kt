package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchThreadGuard
import dev.opencode.mobile.markdown.CodeTokenRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LabViewerViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun openingShowsPlainTextWithoutRunningAnEngine() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val engine = WordEngine("alpha", listOf("fun"))
            val store = smallStore()
            val id = store.addPasted("kotlin", KOTLIN_SOURCE).id
            val model = LabViewerViewModel(store, labEngines(dispatcher, listOf(engine)))

            model.bind(id)
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(LabViewerPhase.Plain, state.phase)
            assertNull(state.timing)
            assertNull(state.selectedEngineId)
            assertTrue(state.lines.all { it.spans.isEmpty() })
            assertEquals(0, engine.instances)
        }
    }

    @Test
    fun tappingAnEngineRendersWithItAndShowsItsTiming() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val store = smallStore()
            val id = store.addPasted("kotlin", KOTLIN_SOURCE).id
            val model = LabViewerViewModel(store, labEngines(dispatcher))
            model.bind(id)
            advanceUntilIdle()

            model.render("alpha")
            assertEquals(LabViewerPhase.Rendering, model.state.value.phase)
            assertEquals("alpha", model.state.value.selectedEngineId)
            advanceUntilIdle()
            var state = model.state.value
            assertEquals(LabViewerPhase.Rendered, state.phase)
            assertEquals("alpha", state.shownEngineId)
            assertEquals(2, state.timing?.spanCount)
            assertTrue(state.timing?.cold == true)
            val firstLine = state.lines.first()
            val firstSpan = firstLine.spans.single()
            assertEquals(CodeTokenRole.Keyword, firstSpan.role)

            model.render("beta")
            advanceUntilIdle()
            state = model.state.value
            assertEquals("beta", state.shownEngineId)
            assertEquals(3, state.timing?.spanCount)

            // The same engine again reuses its warm instance.
            model.render("alpha")
            advanceUntilIdle()
            val warm = model.state.value.timing
            assertFalse(warm?.cold == true)
        }
    }

    @Test
    fun enginesWithoutTheLanguageAreDisabledWithAReason() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val pythonOnly = LanguageEngine("core", "python")
            val store = smallStore()
            val id = store.addPasted("kotlin", KOTLIN_SOURCE).id
            val model = LabViewerViewModel(store, labEngines(dispatcher, listOf(baselineEngine, pythonOnly)))
            model.bind(id)
            advanceUntilIdle()

            val buttons = model.state.value.engines
            val core = buttons.single { it.id == "core" }
            assertFalse(core.enabled)
            assertEquals("Core has no kotlin support yet", core.disabledReason)
            assertTrue(buttons.single { it.id == "alpha" }.enabled)

            model.render("core")
            assertEquals(LabViewerPhase.Plain, model.state.value.phase)
            assertEquals(0, pythonOnly.instances)
        }
    }

    @Test
    fun viewerShowsAMissingDataSet() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val model = LabViewerViewModel(smallStore(), labEngines(dispatcher))
            model.bind("custom/gone")
            advanceUntilIdle()
            assertEquals(LabViewerPhase.Missing, model.state.value.phase)
            val buttons = model.state.value.engines
            assertTrue(buttons.isEmpty())
        }
    }

    /** Real threads: an engine that ignores cancellation must not hold up Cancel or a switch. */
    @Test
    fun cancelAndSwitchDoNotWaitForAnEngineThatIgnoresCancellation() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val executor = Executors.newFixedThreadPool(4)
        val stuck = StuckEngine()
        try {
            val engines = LabEngines(listOf(stuck, baselineEngine), { HighlightBenchThreadGuard() }, executor.asCoroutineDispatcher())
            val store = smallStore()
            val id = store.addPasted("kotlin", KOTLIN_SOURCE).id
            val model = LabViewerViewModel(store, engines)
            runBlocking {
                model.bind(id)
                model.awaitState { it.phase == LabViewerPhase.Plain }

                model.render("stuck")
                assertTrue(stuck.started.await(5, TimeUnit.SECONDS))
                model.cancel()
                // Cancel is immediate: plain text, and the stuck call is listed as still busy.
                var state = model.state.value
                assertEquals(LabViewerPhase.Plain, state.phase)
                assertTrue(state.cancelled)
                assertTrue(state.lines.all { it.spans.isEmpty() })
                state = model.awaitState { it.busyInBackground == listOf("Stuck") }
                assertEquals("Previous Stuck run still busy in the background", state.backgroundNote)

                // A second stuck render, then another engine mid-render: the new engine finishes.
                model.render("stuck")
                model.render("alpha")
                state = model.awaitState { it.phase == LabViewerPhase.Rendered }
                assertEquals("alpha", state.shownEngineId)
                assertEquals(2, state.timing?.spanCount)

                // Once the stuck calls return, nothing is listed any more.
                stuck.release.countDown()
                state = model.awaitState { it.busyInBackground.isEmpty() }
                assertNull(state.backgroundNote)
            }
        } finally {
            stuck.release.countDown()
            executor.shutdownNow()
        }
    }

    private suspend fun LabViewerViewModel.awaitState(predicate: (LabViewerUiState) -> Boolean): LabViewerUiState =
        withTimeout(5_000) { state.first(predicate) }

    /** Supports one language only. */
    private class LanguageEngine(
        override val id: String,
        private val language: String,
    ) : HighlightBenchEngine {
        override val displayName: String = id.replaceFirstChar { it.uppercase() }

        var instances = 0
            private set

        override fun supportsLanguage(language: String): Boolean = language == this.language

        override fun newHighlighter(): HighlightBenchHighlighter {
            instances += 1
            return HighlightBenchHighlighter { _, _, _, _ ->
                HighlightBenchOutput("Highlighted", 0, highlighted = true, spans = emptyList())
            }
        }
    }

    /** Blocks every call until [release], ignoring its cancel signal. */
    private class StuckEngine : HighlightBenchEngine {
        override val id: String = "stuck"
        override val displayName: String = "Stuck"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun newHighlighter(): HighlightBenchHighlighter =
            HighlightBenchHighlighter { _, _, _, _ ->
                started.countDown()
                release.await()
                HighlightBenchOutput("Highlighted", 0, highlighted = true, spans = emptyList())
            }
    }
}
