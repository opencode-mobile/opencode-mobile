package dev.opencode.mobile.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.highlight.bench.HighlightBenchCase
import dev.opencode.mobile.highlight.bench.HighlightBenchPlan
import dev.opencode.mobile.highlight.bench.HighlightBenchProgress
import dev.opencode.mobile.highlight.bench.HighlightBenchReport
import dev.opencode.mobile.highlight.bench.HighlightBenchRun
import dev.opencode.mobile.highlight.bench.HighlightBenchSection
import dev.opencode.mobile.highlight.bench.HighlightBenchTier
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeHighlightCoreCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LabBenchmarkScope(
    val label: String,
) {
    Quick("Quick"),
    Full("Full"),
    Selected("Selected data sets"),
}

/** Where a run is: "case n of m" and what it is measuring. */
data class LabBenchmarkProgress(
    /** One-based. */
    val step: Int,
    val total: Int,
    val caseTitle: String,
    val section: String,
    val engineName: String,
) {
    val label: String
        get() = "Case $step of $total"

    val detail: String
        get() = "$section · $caseTitle · $engineName"

    val fraction: Float
        get() = if (total == 0) 0f else (step - 1).coerceAtLeast(0).toFloat() / total
}

/** One report section as a table: [headers] then [rows] of cells. */
data class LabReportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)

/** A case on the baseline engine against a candidate; [ratio] is candidate time over baseline. */
data class LabComparisonRow(
    val caseTitle: String,
    val section: String,
    val candidate: String,
    val baselineMs: String,
    val candidateMs: String,
    val ratio: String,
)

data class LabBenchmarkUiState(
    val scope: LabBenchmarkScope = LabBenchmarkScope.Quick,
    val dataSets: List<LabDataSetRow> = emptyList(),
    val selectedDataSetIds: List<String> = emptyList(),
    val engines: List<LabEngineOption> = emptyList(),
    /** Picked engines in run order; the first is the comparison baseline. */
    val engineOrder: List<String> = emptyList(),
    val running: Boolean = false,
    val stopping: Boolean = false,
    val progress: LabBenchmarkProgress? = null,
    val tables: List<LabReportTable> = emptyList(),
    val baselineName: String? = null,
    val comparisons: List<LabComparisonRow> = emptyList(),
    /** The report of the last run in this screen, or the saved one from an earlier run. */
    val report: LabSavedReport? = null,
    /** True when [report] came from storage, not from a run in this screen. */
    val reportFromStorage: Boolean = false,
    /** The stored report as Markdown blocks, parsed off the main thread; null until parsed. */
    val storedReportDocument: MarkdownDocument? = null,
    val error: String? = null,
) {
    val canRun: Boolean
        get() = !running && engineOrder.isNotEmpty() && (scope != LabBenchmarkScope.Selected || selectedDataSetIds.isNotEmpty())

    /** Engine order for display: "1. highlight-core (baseline)". */
    fun engineLabel(option: LabEngineOption): String {
        val position = engineOrder.indexOf(option.id)
        return when {
            position < 0 -> option.name
            position == 0 && engineOrder.size > 1 -> "1. ${option.name} (baseline)"
            else -> "${position + 1}. ${option.name}"
        }
    }
}

/**
 * Benchmark: a tier or picked data sets on picked engines, with progress, Stop, tables per
 * section, the comparison against the first engine, and the Markdown and JSON reports, which are
 * saved so the last one survives the screen.
 */
@HiltViewModel
class LabBenchmarkViewModel
    @Inject
    constructor(
        private val store: LabDataSetStore,
        private val engines: LabEngines,
        private val reports: LabReportStore,
        private val device: LabDeviceInfo,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow(
                LabBenchmarkUiState(
                    dataSets = store.all().map { it.toRow(store.knownLength(it.id)) },
                    engines = engines.options,
                    engineOrder = engines.options.map { it.id },
                ),
            )
        val state: StateFlow<LabBenchmarkUiState> = mutableState.asStateFlow()

        private var run: HighlightBenchRun? = null

        init {
            viewModelScope.launch {
                val (saved, document) =
                    withContext(engines.dispatcher) {
                        val saved = reports.latest()
                        saved to saved?.let { report -> parseReport(report.markdown) }
                    }
                if (saved != null) {
                    mutableState.update {
                        if (it.report == null) it.copy(report = saved, reportFromStorage = true, storedReportDocument = document) else it
                    }
                }
            }
            // The picker shows characters like the Data sets screen; small data sets come first.
            viewModelScope.launch {
                store
                    .all()
                    .filter { store.knownLength(it.id) == null }
                    .sortedBy { it.sizeClass.ordinal }
                    .forEach { dataSet ->
                        withContext(engines.dispatcher) { store.length(dataSet) }
                        mutableState.update { state -> state.copy(dataSets = store.all().map { it.toRow(store.knownLength(it.id)) }) }
                    }
            }
        }

        fun selectScope(scope: LabBenchmarkScope) {
            if (mutableState.value.running) return
            mutableState.update { it.copy(scope = scope) }
        }

        fun toggleDataSet(id: String) {
            if (mutableState.value.running) return
            mutableState.update {
                it.copy(selectedDataSetIds = if (id in it.selectedDataSetIds) it.selectedDataSetIds - id else it.selectedDataSetIds + id)
            }
        }

        /** Adds an engine at the end of the order, or removes it. */
        fun toggleEngine(id: String) {
            if (mutableState.value.running || engines.engine(id) == null) return
            mutableState.update {
                it.copy(engineOrder = if (id in it.engineOrder) it.engineOrder - id else it.engineOrder + id)
            }
        }

        fun run() {
            val state = mutableState.value
            if (!state.canRun) return
            val picked = state.engineOrder.mapNotNull(engines::engine)
            val cases = cases(state)
            if (cases.isEmpty()) {
                mutableState.update { it.copy(error = "No cases to run.") }
                return
            }
            val run = HighlightBenchRun(picked, cases, engines.settings, engines.callGuard())
            this.run = run
            mutableState.update {
                it.copy(
                    running = true,
                    stopping = false,
                    progress = null,
                    tables = emptyList(),
                    comparisons = emptyList(),
                    baselineName = picked.first().displayName,
                    error = null,
                )
            }
            val title = "Highlighting Lab run: ${state.scope.label.lowercase()} (${picked.joinToString { it.displayName }})"
            viewModelScope.launch {
                val outcome =
                    withContext(engines.dispatcher) {
                        runCatching {
                            val report = run.run(::publish)
                            val saved = runCatching { reports.save(report.markdown(title, device.environment), report.json()) }
                            report to saved
                        }
                    }
                this@LabBenchmarkViewModel.run = null
                outcome
                    .onSuccess { (report, saved) ->
                        mutableState.update {
                            it.copy(
                                running = false,
                                stopping = false,
                                tables = tables(report),
                                comparisons = comparisons(report),
                                report = saved.getOrNull() ?: it.report,
                                reportFromStorage = saved.isFailure && it.reportFromStorage,
                                error = saved.exceptionOrNull()?.let { failure -> "Couldn't save the report: ${failure.message}" },
                            )
                        }
                    }.onFailure { failure ->
                        mutableState.update { it.copy(running = false, stopping = false, error = "Run failed: ${failure.message}") }
                    }
            }
        }

        private fun parseReport(markdown: String): MarkdownDocument? =
            runCatching {
                OpenCodeMarkdownParser.parse(markdown, codeHighlighter = OpenCodeHighlightCoreCodeHighlighter)
            }.getOrNull()

        /** Cancels the call in progress and skips the remaining cases; the partial report is kept. */
        fun stop() {
            val run = run ?: return
            run.stop()
            mutableState.update { it.copy(stopping = true) }
        }

        override fun onCleared() {
            run?.stop()
        }

        private fun cases(state: LabBenchmarkUiState): List<HighlightBenchCase> =
            when (state.scope) {
                LabBenchmarkScope.Quick -> HighlightBenchPlan.cases(HighlightBenchTier.Quick)
                LabBenchmarkScope.Full -> HighlightBenchPlan.cases(HighlightBenchTier.Full)
                LabBenchmarkScope.Selected -> HighlightBenchPlan.cases(state.selectedDataSetIds.mapNotNull(store::find))
            }

        /** Runs on the benchmark thread; StateFlow updates are thread-safe. */
        private fun publish(progress: HighlightBenchProgress) {
            val step =
                LabBenchmarkProgress(
                    step = progress.index + 1,
                    total = progress.total,
                    caseTitle = progress.case.title,
                    section = progress.report.sectionTitle(progress.case.section),
                    engineName = progress.engineName,
                )
            if (progress.result == null) {
                mutableState.update { it.copy(progress = step) }
            } else {
                val tables = tables(progress.report)
                val comparisons = comparisons(progress.report)
                mutableState.update { it.copy(progress = step, tables = tables, comparisons = comparisons) }
            }
        }

        private fun tables(report: HighlightBenchReport): List<LabReportTable> =
            report.sections.mapNotNull { section ->
                val results = report.results(section)
                if (results.isEmpty()) return@mapNotNull null
                LabReportTable(report.sectionTitle(section), report.headers(section), results.map { it.cells })
            }

        private fun comparisons(report: HighlightBenchReport): List<LabComparisonRow> =
            report.comparisons().map { comparison ->
                LabComparisonRow(
                    caseTitle = comparison.case.title,
                    section = comparison.case.section.shortLabel,
                    candidate = comparison.candidate.engineName,
                    baselineMs = comparison.baselineMs?.let(LabFormat::ms) ?: comparison.baseline.status.name,
                    candidateMs = comparison.candidateMs?.let(LabFormat::ms) ?: comparison.candidate.status.name,
                    ratio = comparison.timeRatio?.let(LabFormat::ratio) ?: "—",
                )
            }
    }

/** A section's name for narrow table columns; report headings keep the full title. */
internal val HighlightBenchSection.shortLabel: String
    get() =
        when (this) {
            HighlightBenchSection.ColdStart -> "Cold start"
            HighlightBenchSection.Throughput -> "Throughput"
            HighlightBenchSection.ChatFences -> "Chat fences"
            HighlightBenchSection.LargeFiles -> "1 MB files"
            HighlightBenchSection.WorstCases -> "Worst cases"
            HighlightBenchSection.Custom -> "Custom"
        }
