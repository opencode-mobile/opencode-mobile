package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineStyleOption
import dev.opencode.mobile.ui.settings.settingsTitleRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionChatTimelineOffersRegionState(
    /** The notice the picker's answer leaves. */
    val notice: UiText? = null,
    /** One-time Timeline Style picker (#805). */
    val timelineStylePickerVisible: Boolean = false,
    /** Taken when the picker shows, so its rows stay put while the sheet slides out. */
    val timelineStylePickerOptions: List<SessionChatTimelineStyleOption> = emptyList(),
)

/**
 * Session Chat region for the one-time Timeline Style picker after the first history load
 * (#805), the notice it leaves, and its preference writes.
 *
 * Actions change state silently and return whether the facade should log and republish; only
 * the delayed offer calls [onChange] itself.
 */
class SessionChatTimelineOffers(
    private val scope: CoroutineScope,
    /** A permission, question, or screen sheet is up, so the picker may not rise. */
    private val isBlocked: () -> Boolean,
    private val onChange: () -> Unit,
) {
    private val _state = MutableStateFlow(SessionChatTimelineOffersRegionState())
    val state: StateFlow<SessionChatTimelineOffersRegionState> = _state.asStateFlow()

    // The picker is offered once per ViewModel, after the first history load shows messages.
    private var timelineStylePickerChecked = false
    private var timelineStylePickerJob: Job? = null

    /** Picker → Use. Returns false (and changes nothing) when the picker is not up. */
    fun chooseTimelineStyle(style: SessionChatTimelineStyle): Boolean {
        if (!_state.value.timelineStylePickerVisible) return false
        _state.update { it.copy(timelineStylePickerVisible = false) }
        SessionChatDisplayPreferences.updateTimelineStyle(style)
        _state.update { it.copy(notice = timelineStylePickerNotice(style)) }
        return true
    }

    /** Picker swipe, scrim, back, or close: keeps the recommended Activity Card. */
    fun dismissTimelineStylePicker(): Boolean {
        if (!_state.value.timelineStylePickerVisible) return false
        _state.update { it.copy(timelineStylePickerVisible = false) }
        SessionChatDisplayPreferences.dismissTimelineStylePicker()
        _state.update { it.copy(notice = timelineStylePickerNotice(SessionChatTimelineStyle.ActivityCard)) }
        return true
    }

    /**
     * The first settled history load decides the picker for this ViewModel: messages on
     * screen schedule it; a new or empty session skips it. A failed load waits for a retry.
     */
    fun observeTimelineLoad(state: SessionChatTimelineState) {
        if (timelineStylePickerChecked) return
        when (state) {
            SessionChatTimelineState.Loading, SessionChatTimelineState.Error -> return
            SessionChatTimelineState.Draft, SessionChatTimelineState.Empty -> {
                timelineStylePickerChecked = true
                return
            }
            SessionChatTimelineState.Ready -> timelineStylePickerChecked = true
        }
        if (!SessionChatDisplayPreferences.timelineStylePickerPending) return
        timelineStylePickerJob?.cancel()
        timelineStylePickerJob =
            scope.launch {
                delay(TIMELINE_STYLE_PICKER_DELAY_MILLIS)
                if (!SessionChatDisplayPreferences.timelineStylePickerPending) return@launch
                // Blocked: skip for this ViewModel; the next session open offers it again.
                if (isBlocked()) return@launch
                val options =
                    SessionChatTimelineStyleOption.pickerOptions(
                        available = SessionChatDisplayPreferences.availableTimelineStyles,
                        current = SessionChatDisplayPreferences.timelineStyle,
                    )
                _state.update { it.copy(timelineStylePickerOptions = options, timelineStylePickerVisible = true) }
                onChange()
            }
    }

    /** A new session or draft clears the notice; the picker stays decided for this ViewModel. */
    fun reset() {
        _state.update { it.copy(notice = null) }
    }

    /** The snackbar was dismissed. */
    fun clearNotices() {
        _state.update { it.copy(notice = null) }
    }

    companion object {
        /** Pause after the first history load before the Timeline Style picker rises (#805). */
        const val TIMELINE_STYLE_PICKER_DELAY_MILLIS = 600L

        /** "Timeline: Step Rail. Change it any time in Settings.", with the keyed style name. */
        fun timelineStylePickerNotice(style: SessionChatTimelineStyle): UiText =
            UiText.resource(R.string.session_chat_timeline_style_notice, UiText.resource(style.settingsTitleRes()))
    }
}
