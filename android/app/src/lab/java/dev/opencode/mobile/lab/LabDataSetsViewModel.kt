package dev.opencode.mobile.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.highlight.bench.HighlightBenchDataKind
import dev.opencode.mobile.highlight.bench.HighlightBenchSizeClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LabDataSetsUiState(
    val languages: List<String> = emptyList(),
    val language: String? = null,
    val sizeClass: HighlightBenchSizeClass? = null,
    val kind: HighlightBenchDataKind? = null,
    val rows: List<LabDataSetRow> = emptyList(),
) {
    val sizeClasses: List<HighlightBenchSizeClass>
        get() = HighlightBenchSizeClass.entries

    val kinds: List<HighlightBenchDataKind>
        get() = HighlightBenchDataKind.entries
}

/** Data sets: the catalog and custom data sets, filtered by language, size class, and kind. */
@HiltViewModel
class LabDataSetsViewModel
    @Inject
    constructor(
        private val store: LabDataSetStore,
        private val engines: LabEngines,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(LabDataSetsUiState())
        val state: StateFlow<LabDataSetsUiState> = mutableState.asStateFlow()

        init {
            refresh()
            measureLengths()
        }

        fun selectLanguage(language: String?) {
            mutableState.update { it.copy(language = language) }
            refresh()
        }

        fun selectSizeClass(sizeClass: HighlightBenchSizeClass?) {
            mutableState.update { it.copy(sizeClass = sizeClass) }
            refresh()
        }

        fun selectKind(kind: HighlightBenchDataKind?) {
            mutableState.update { it.copy(kind = kind) }
            refresh()
        }

        /** Rebuilds the rows, for example after a paste added a custom data set. */
        fun refresh() {
            mutableState.update { state ->
                state.copy(
                    languages = store.languages,
                    rows =
                        store
                            .filter(state.language, state.sizeClass, state.kind)
                            .map { it.toRow(store.knownLength(it.id)) },
                )
            }
        }

        /** Character counts need each text generated once; small ones come first. */
        private fun measureLengths() {
            viewModelScope.launch {
                store
                    .all()
                    .filter { store.knownLength(it.id) == null }
                    .sortedBy { it.sizeClass.ordinal }
                    .forEach { dataSet ->
                        withContext(engines.dispatcher) { store.length(dataSet) }
                        refresh()
                    }
            }
        }
    }
