package dev.opencode.mobile.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.highlight.bench.HighlightBenchSpanDiff
import dev.opencode.mobile.ui.components.OpenCodeCodeLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LabComparePhase {
    /** No data set yet: the screen lists them. */
    Picking,
    Comparing,
    Compared,
    Missing,
}

/** One engine's side of a comparison. */
data class LabComparePane(
    val engineName: String,
    val lines: List<OpenCodeCodeLine>,
    val timing: LabTiming,
)

/** Where the two engines disagree, per character (colored against plain counts). */
data class LabCompareSummary(
    val agreement: Double,
    val differingRanges: Int,
    val differingCharacters: Int,
) {
    val agreementLabel: String
        get() = "${LabFormat.percent(agreement)} agree"

    val differenceLabel: String
        get() =
            "${LabFormat.count(differingRanges)} differing ${if (differingRanges == 1) "range" else "ranges"}, " +
                "${LabFormat.count(differingCharacters)} chars"
}

data class LabCompareUiState(
    val dataSetId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val pickerRows: List<LabDataSetRow> = emptyList(),
    val engines: List<LabEngineOption> = emptyList(),
    val leftEngineId: String? = null,
    val rightEngineId: String? = null,
    val phase: LabComparePhase = LabComparePhase.Picking,
    val left: LabComparePane? = null,
    val right: LabComparePane? = null,
    val summary: LabCompareSummary? = null,
    /** The screen opened on the picker, so Back from a comparison returns to it. */
    val opensOnPicker: Boolean = false,
) {
    val backReturnsToPicker: Boolean
        get() = opensOnPicker && phase != LabComparePhase.Picking
}

/** Two engines on one data set: both highlighted, differing ranges marked, agreement share. */
@HiltViewModel
class LabCompareViewModel
    @Inject
    constructor(
        private val store: LabDataSetStore,
        private val engines: LabEngines,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow(
                engines.options.let { options ->
                    LabCompareUiState(
                        engines = options,
                        leftEngineId = options.firstOrNull()?.id,
                        rightEngineId = options.getOrNull(1)?.id ?: options.firstOrNull()?.id,
                    )
                },
            )
        val state: StateFlow<LabCompareUiState> = mutableState.asStateFlow()

        private var bound = false
        private var compareJob: Job? = null

        /** [dataSetId] from the route; null shows the data set list first. */
        fun bind(dataSetId: String?) {
            if (bound) return
            bound = true
            mutableState.update { it.copy(opensOnPicker = dataSetId == null) }
            if (dataSetId == null) showPicker() else selectDataSet(dataSetId)
        }

        fun selectDataSet(dataSetId: String) {
            val dataSet = store.find(dataSetId)
            mutableState.update {
                it.copy(
                    dataSetId = dataSetId,
                    title = dataSet?.displayName ?: dataSetId,
                    subtitle = dataSet?.let { found -> "${found.language} · ${found.sizeClass.label}" }.orEmpty(),
                    phase = if (dataSet == null) LabComparePhase.Missing else LabComparePhase.Comparing,
                )
            }
            if (dataSet != null) compare()
        }

        /** Back to the data set list. */
        fun changeDataSet() {
            compareJob?.cancel()
            showPicker()
        }

        fun selectLeftEngine(engineId: String) {
            if (engines.engine(engineId) == null) return
            mutableState.update { it.copy(leftEngineId = engineId) }
            if (mutableState.value.phase != LabComparePhase.Picking) compare()
        }

        fun selectRightEngine(engineId: String) {
            if (engines.engine(engineId) == null) return
            mutableState.update { it.copy(rightEngineId = engineId) }
            if (mutableState.value.phase != LabComparePhase.Picking) compare()
        }

        private fun showPicker() {
            mutableState.update {
                it.copy(
                    dataSetId = null,
                    title = "",
                    subtitle = "",
                    phase = LabComparePhase.Picking,
                    pickerRows = store.all().map { dataSet -> dataSet.toRow(store.knownLength(dataSet.id)) },
                    left = null,
                    right = null,
                    summary = null,
                )
            }
        }

        private fun compare() {
            val state = mutableState.value
            val dataSet = state.dataSetId?.let(store::find) ?: return
            val leftId = state.leftEngineId ?: return
            val rightId = state.rightEngineId ?: return
            compareJob?.cancel()
            mutableState.update { it.copy(phase = LabComparePhase.Comparing, left = null, right = null, summary = null) }
            compareJob =
                viewModelScope.launch {
                    val source = withContext(engines.dispatcher) { dataSet.generate() }
                    val left = engines.probe(leftId, dataSet.language, source)
                    val right = engines.probe(rightId, dataSet.language, source)
                    val result =
                        withContext(engines.dispatcher) {
                            val leftSpans = left.call.spans.orEmpty()
                            val rightSpans = right.call.spans.orEmpty()
                            val diff = HighlightBenchSpanDiff.compare(source.length, leftSpans, rightSpans)
                            Triple(
                                LabComparePane(
                                    engineName(leftId),
                                    labCodeLines(source, leftSpans.toCodeSpans(), diff.differingRanges),
                                    LabTiming.of(left, source.length),
                                ),
                                LabComparePane(
                                    engineName(rightId),
                                    labCodeLines(source, rightSpans.toCodeSpans(), diff.differingRanges),
                                    LabTiming.of(right, source.length),
                                ),
                                LabCompareSummary(diff.agreement, diff.differingRangeCount, diff.differingCharacters),
                            )
                        }
                    mutableState.update {
                        it.copy(
                            subtitle = "${dataSet.language} · ${dataSet.sizeClass.label} · ${LabFormat.count(source.length)} chars",
                            phase = LabComparePhase.Compared,
                            left = result.first,
                            right = result.second,
                            summary = result.third,
                        )
                    }
                }
        }

        private fun engineName(id: String): String = engines.engine(id)?.displayName ?: id
    }
