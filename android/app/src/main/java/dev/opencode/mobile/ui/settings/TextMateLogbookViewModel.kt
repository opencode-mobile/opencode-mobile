package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.textmate.TextMateLogbook
import dev.opencode.mobile.domain.textmate.TextMateLogbookEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TextMateLogbookUiState(
    val loading: Boolean = true,
    val entries: List<TextMateLogbookEntry> = emptyList(),
    val droppedEntries: Long = 0,
    val storageError: String? = null,
)

@HiltViewModel
class TextMateLogbookViewModel
    @Inject
    constructor(
        private val logbook: TextMateLogbook,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(TextMateLogbookUiState())
        val state: StateFlow<TextMateLogbookUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                mutableState.update { it.copy(loading = true) }
                val snapshot = logbook.snapshot()
                mutableState.value =
                    TextMateLogbookUiState(
                        loading = false,
                        entries = snapshot.entries,
                        droppedEntries = snapshot.droppedEntries,
                        storageError = snapshot.storageError,
                    )
            }
        }

        fun clear() {
            viewModelScope.launch {
                logbook.clear()
                refresh()
            }
        }
    }
