package dev.opencode.mobile.ui.whatsnew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.releasenotes.ReleaseNote
import dev.opencode.mobile.domain.releasenotes.WhatsNew
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Settings → What's new: every release up to the installed one, newest first. */
data class WhatsNewHistoryUiState(
    val installedVersion: String = "",
    val releases: List<ReleaseNote> = emptyList(),
    val loaded: Boolean = false,
)

/** Opening the history clears the Settings New badge and counts pending notes as seen. */
@HiltViewModel
class WhatsNewHistoryViewModel internal constructor(
    private val whatsNew: WhatsNew,
    ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(whatsNew: WhatsNew) : this(whatsNew, Dispatchers.IO)

    private val _state = MutableStateFlow(WhatsNewHistoryUiState(installedVersion = whatsNew.installedVersion))
    val state: StateFlow<WhatsNewHistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val releases =
                withContext(ioDispatcher) {
                    // Reading the history counts pending notes as seen, so the sheet won't repeat them.
                    whatsNew.markSeenInHistory()
                    whatsNew.history()
                }
            _state.update { it.copy(releases = releases, loaded = true) }
        }
    }
}
