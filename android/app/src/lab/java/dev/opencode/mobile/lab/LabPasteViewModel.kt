package dev.opencode.mobile.lab

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class LabPasteUiState(
    val text: String = "",
    val language: String = "",
    val languages: List<String> = emptyList(),
) {
    val canOpen: Boolean
        get() = text.isNotEmpty() && language.isNotEmpty()

    val characterLabel: String
        get() = "${LabFormat.count(text.length)} characters"
}

/** Paste text: pick its language and open it as a custom data set. */
@HiltViewModel
class LabPasteViewModel
    @Inject
    constructor(
        private val store: LabDataSetStore,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow(
                store.languages.let { languages -> LabPasteUiState(language = languages.firstOrNull().orEmpty(), languages = languages) },
            )
        val state: StateFlow<LabPasteUiState> = mutableState.asStateFlow()

        fun setText(text: String) {
            mutableState.update { it.copy(text = text) }
        }

        fun selectLanguage(language: String) {
            mutableState.update { it.copy(language = language) }
        }

        /** Adds the text as a custom data set and returns its id, or null when there is nothing to open. */
        fun open(): String? {
            val state = mutableState.value
            if (!state.canOpen) return null
            return store.addPasted(state.language, state.text).id
        }
    }
