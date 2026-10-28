package dev.opencode.mobile.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.highlight.bench.HighlightBenchDataSet
import dev.opencode.mobile.ui.components.OpenCodeCodeLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LabViewerPhase {
    /** Generating the text. */
    Loading,

    /** Plain text, no engine running: on open, and after Cancel. */
    Plain,

    /** Plain text shown while [LabViewerUiState.renderingEngineId] runs. */
    Rendering,

    /** Spans from [LabViewerUiState.shownEngineId] with its timing. */
    Rendered,

    /** The data set is gone (a custom one from before a process restart). */
    Missing,
}

/** An engine button; a disabled one says why. */
data class LabViewerEngine(
    val id: String,
    val name: String,
    val disabledReason: String? = null,
) {
    val enabled: Boolean
        get() = disabledReason == null
}

data class LabViewerUiState(
    val dataSetId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val engines: List<LabViewerEngine> = emptyList(),
    val lines: List<OpenCodeCodeLine> = emptyList(),
    val phase: LabViewerPhase = LabViewerPhase.Loading,
    val renderingEngineId: String? = null,
    val shownEngineId: String? = null,
    val timing: LabTiming? = null,
    /** True after Cancel, until the next render. */
    val cancelled: Boolean = false,
    /** Engines whose earlier calls ignored Cancel past the grace and are still running. */
    val busyInBackground: List<String> = emptyList(),
) {
    /** The engine the buttons mark: the one rendering, else the one whose spans are shown. */
    val selectedEngineId: String?
        get() = renderingEngineId ?: shownEngineId

    fun engineName(id: String?): String = engines.firstOrNull { it.id == id }?.name ?: id.orEmpty()

    /** A line under the strip about engine calls still running after Cancel or a switch. */
    val backgroundNote: String?
        get() =
            busyInBackground
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { names -> "Previous ${names.joinToString()} run still busy in the background" }
}

/**
 * One data set as plain text, highlighted only when an engine button is tapped. A render never
 * blocks the screen: Cancel or another engine cancels the engine call, stops waiting for it at
 * once, and shows plain text (or starts the other engine); a call that ignores the cancel is
 * listed until it returns.
 */
@HiltViewModel
class LabViewerViewModel
    @Inject
    constructor(
        private val store: LabDataSetStore,
        private val engines: LabEngines,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(LabViewerUiState())
        val state: StateFlow<LabViewerUiState> = mutableState.asStateFlow()

        private var dataSet: HighlightBenchDataSet? = null
        private var source: String? = null
        private var plainLines: List<OpenCodeCodeLine> = emptyList()
        private var renderJob: Job? = null

        init {
            viewModelScope.launch {
                engines.busyInBackground.collect { busy -> mutableState.update { it.copy(busyInBackground = busy) } }
            }
        }

        fun bind(dataSetId: String) {
            if (mutableState.value.dataSetId == dataSetId) return
            val found = store.find(dataSetId)
            dataSet = found
            mutableState.update {
                it.copy(
                    dataSetId = dataSetId,
                    title = found?.displayName ?: dataSetId,
                    engines = found?.let(::engineButtons).orEmpty(),
                    phase = if (found == null) LabViewerPhase.Missing else LabViewerPhase.Loading,
                )
            }
            if (found == null) return
            viewModelScope.launch {
                val (text, plain) =
                    withContext(engines.dispatcher) {
                        val text = found.generate()
                        text to labCodeLines(text, emptyList())
                    }
                source = text
                plainLines = plain
                mutableState.update {
                    it.copy(
                        subtitle = "${found.language} · ${found.sizeClass.label} · ${LabFormat.count(text.length)} chars",
                        lines = plain,
                        phase = if (it.phase == LabViewerPhase.Loading) LabViewerPhase.Plain else it.phase,
                    )
                }
            }
        }

        /** Renders with [engineId], replacing a render in progress. Ignored before the text is ready. */
        fun render(engineId: String) {
            val dataSet = dataSet ?: return
            val source = source ?: return
            val button = mutableState.value.engines.firstOrNull { it.id == engineId }
            if (button?.enabled != true) return
            renderJob?.cancel()
            mutableState.update {
                it.copy(
                    lines = plainLines,
                    phase = LabViewerPhase.Rendering,
                    renderingEngineId = engineId,
                    shownEngineId = null,
                    timing = null,
                    cancelled = false,
                )
            }
            renderJob =
                viewModelScope.launch {
                    val probe = engines.probe(engineId, dataSet.language, source)
                    val spans = probe.call.spans.orEmpty()
                    val lines = withContext(engines.dispatcher) { labCodeLines(source, spans.toCodeSpans()) }
                    mutableState.update {
                        it.copy(
                            lines = lines,
                            phase = LabViewerPhase.Rendered,
                            renderingEngineId = null,
                            shownEngineId = engineId,
                            timing = LabTiming.of(probe, source.length),
                        )
                    }
                }
        }

        /** Stops waiting for the render in progress and shows plain text. */
        fun cancel() {
            if (mutableState.value.phase != LabViewerPhase.Rendering) return
            renderJob?.cancel()
            renderJob = null
            mutableState.update {
                it.copy(
                    lines = plainLines,
                    phase = LabViewerPhase.Plain,
                    renderingEngineId = null,
                    shownEngineId = null,
                    timing = null,
                    cancelled = true,
                )
            }
        }

        private fun engineButtons(dataSet: HighlightBenchDataSet): List<LabViewerEngine> =
            engines.engines.map { engine ->
                LabViewerEngine(
                    id = engine.id,
                    name = engine.displayName,
                    disabledReason =
                        if (engine.supportsLanguage(dataSet.language)) {
                            null
                        } else {
                            "${engine.displayName} has no ${dataSet.language} support yet"
                        },
                )
            }
    }
