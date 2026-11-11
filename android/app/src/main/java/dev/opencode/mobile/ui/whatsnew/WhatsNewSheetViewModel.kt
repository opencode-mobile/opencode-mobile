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

/** The What's new sheet over Home. [releases] stay set after dismissal so the sheet exits with its content. */
data class WhatsNewSheetUiState(
    val visible: Boolean = false,
    val installedVersion: String = "",
    /** Newest first; more than one when updates were skipped. */
    val releases: List<ReleaseNote> = emptyList(),
) {
    /** Releases after the first, which the subtitle counts as skipped updates. */
    val skippedCount: Int
        get() = (releases.size - 1).coerceAtLeast(0)
}

/**
 * Shows What's new once after an update, the first time Home is idle in this process. The
 * releases count as seen the moment the sheet appears; until then they stay pending.
 */
@HiltViewModel
class WhatsNewSheetViewModel internal constructor(
    private val whatsNew: WhatsNew,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(whatsNew: WhatsNew) : this(whatsNew, Dispatchers.IO)

    private val _state = MutableStateFlow(WhatsNewSheetUiState(installedVersion = whatsNew.installedVersion))
    val state: StateFlow<WhatsNewSheetUiState> = _state.asStateFlow()

    /** Null until the launch decision has loaded. */
    private var pending: List<ReleaseNote>? = null
    private var homeIdle = false

    init {
        viewModelScope.launch {
            pending = withContext(ioDispatcher) { whatsNew.pendingReleases() }
            presentIfReady()
        }
    }

    /** Home became the visible idle screen ([idle]), or stopped being it. */
    fun onHomeIdleChange(idle: Boolean) {
        homeIdle = idle
        presentIfReady()
    }

    /** Done, swipe down, or a tap outside. */
    fun dismiss() {
        _state.update { it.copy(visible = false) }
    }

    private fun presentIfReady() {
        val releases = pending ?: return
        if (!homeIdle || releases.isEmpty()) return
        pending = emptyList()
        // Opening the Settings history first already counts them as seen.
        if (!whatsNew.markPresented()) return
        _state.update { it.copy(visible = true, releases = releases) }
    }
}
