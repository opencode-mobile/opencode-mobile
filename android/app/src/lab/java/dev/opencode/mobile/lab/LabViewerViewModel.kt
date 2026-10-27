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

    /** Plain text shown; the engine call is running. */
    Highlighting,
    Highlighted,

    /** The data set is gone (a custom one from before a process restart). */
    Missing,
}

data class LabViewerUiState(
    val dataSetId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val engines: List<LabEngineOption> = emptyList(),
    val engineId: String? = null,
    val lines: List<OpenCodeCodeLine> = emptyList(),
    val phase: LabViewerPhase = LabViewerPhase.Loading,
    val timing: LabTiming? = null,
)

/** One data set highlighted by the picked engine, with the call's timing. */
@HiltViewModel
class LabViewerViewModel
    @Inject
    constructor(
        private val store: LabDataSetStore,
        private val engines: LabEngines,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow(LabViewerUiState(engines = engines.options, engineId = engines.options.firstOrNull()?.id))
        val state: StateFlow<LabViewerUiState> = mutableState.asStateFlow()

        private var dataSet: HighlightBenchDataSet? = null
        private var source: String? = null
        private var highlightJob: Job? = null

        fun bind(dataSetId: String) {
            if (mutableState.value.dataSetId == dataSetId) return
            val found = store.find(dataSetId)
            dataSet = found
            mutableState.update { it.copy(dataSetId = dataSetId, title = found?.displayName ?: dataSetId) }
            if (found == null) {
                mutableState.update { it.copy(phase = LabViewerPhase.Missing) }
                return
            }
            viewModelScope.launch {
                val (text, plain) =
                    withContext(engines.dispatcher) {
                        val text = found.generate()
                        text to labCodeLines(text, emptyList())
                    }
                source = text
                mutableState.update {
                    it.copy(
                        subtitle = "${found.language} · ${found.sizeClass.label} · ${LabFormat.count(text.length)} chars",
                        lines = plain,
                    )
                }
                highlight()
            }
        }

        fun selectEngine(engineId: String) {
            if (engineId == mutableState.value.engineId || engines.engine(engineId) == null) return
            mutableState.update { it.copy(engineId = engineId) }
            if (source != null) highlight()
        }

        private fun highlight() {
            val dataSet = dataSet ?: return
            val source = source ?: return
            val engineId = mutableState.value.engineId ?: return
            highlightJob?.cancel()
            mutableState.update { it.copy(phase = LabViewerPhase.Highlighting, timing = null) }
            highlightJob =
                viewModelScope.launch {
                    val probe = engines.probe(engineId, dataSet.language, source)
                    val lines =
                        withContext(engines.dispatcher) {
                            labCodeLines(
                                source,
                                probe.call.spans
                                    .orEmpty()
                                    .toCodeSpans(),
                            )
                        }
                    mutableState.update {
                        it.copy(
                            lines = lines,
                            phase = LabViewerPhase.Highlighted,
                            timing = LabTiming.of(probe, source.length),
                        )
                    }
                }
        }
    }
