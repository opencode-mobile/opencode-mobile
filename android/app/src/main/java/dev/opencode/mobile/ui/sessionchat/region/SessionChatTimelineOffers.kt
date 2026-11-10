package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.preferences.ChipTimelineIntro
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineStyleOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionChatTimelineOffersRegionState(
    /** One-time Chip timeline intro (OPE-270): offered after a long turn finishes. */
    val chipTimelineIntroVisible: Boolean = false,
    /** The answer notice of either offer; the two share one slot. */
    val chipTimelineIntroNotice: String? = null,
    /** One-time Timeline Style picker (#805). */
    val timelineStylePickerVisible: Boolean = false,
    /** Taken when the picker shows, so its rows stay put while the sheet slides out. */
    val timelineStylePickerOptions: List<SessionChatTimelineStyleOption> = emptyList(),
)

/**
 * Session Chat region for the two one-time timeline style offers: the Chip timeline intro after
 * a long turn settles (OPE-270) and the Timeline Style picker after the first history load
 * (#805), the notice each leaves, and their preference writes.
 *
 * Actions change state silently and return whether the facade should log and republish; only
 * the delayed offers call [onChange] themselves.
 */
class SessionChatTimelineOffers(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    /** The open session's loaded messages; the intro counts the last turn's tool calls. */
    private val loadedMessages: () -> List<Message>,
    /** A permission, question, or screen sheet is up, so neither offer may rise. */
    private val isBlocked: () -> Boolean,
    private val isSessionBusy: () -> Boolean,
    private val onChange: () -> Unit,
) {
    private val _state = MutableStateFlow(SessionChatTimelineOffersRegionState())
    val state: StateFlow<SessionChatTimelineOffersRegionState> = _state.asStateFlow()

    private var chipTimelineIntroTurnActive: Boolean? = null
    private var chipTimelineIntroJob: Job? = null

    // The picker is offered once per ViewModel, after the first history load shows messages.
    private var timelineStylePickerChecked = false
    private var timelineStylePickerJob: Job? = null

    /** Intro → Enable. Returns whether the intro was up (the facade logs Enable only then). */
    fun enableChipTimeline(): Boolean {
        val wasVisible = hideChipTimelineIntro()
        SessionChatDisplayPreferences.updateChipTimeline(true)
        _state.update { it.copy(chipTimelineIntroNotice = CHIP_TIMELINE_INTRO_ENABLED_NOTICE) }
        return wasVisible
    }

    /** Intro → Later button, swipe, scrim, or back. Returns whether the intro was up. */
    fun postponeChipTimelineIntro(now: Long): Boolean {
        val wasVisible = hideChipTimelineIntro()
        SessionChatDisplayPreferences.postponeChipTimelineIntro(now)
        return wasVisible
    }

    /** Intro → Don't show again. Returns whether the intro was up. */
    fun dismissChipTimelineIntro(): Boolean {
        val wasVisible = hideChipTimelineIntro()
        SessionChatDisplayPreferences.finishChipTimelineIntro()
        return wasVisible
    }

    /** Picker → Use. Returns false (and changes nothing) when the picker is not up. */
    fun chooseTimelineStyle(style: SessionChatTimelineStyle): Boolean {
        if (!_state.value.timelineStylePickerVisible) return false
        _state.update { it.copy(timelineStylePickerVisible = false) }
        SessionChatDisplayPreferences.updateTimelineStyle(style)
        _state.update { it.copy(chipTimelineIntroNotice = timelineStylePickerNotice(style)) }
        return true
    }

    /** Picker swipe, scrim, back, or close: keeps the recommended Activity Card. */
    fun dismissTimelineStylePicker(): Boolean {
        if (!_state.value.timelineStylePickerVisible) return false
        _state.update { it.copy(timelineStylePickerVisible = false) }
        SessionChatDisplayPreferences.dismissTimelineStylePicker()
        _state.update { it.copy(chipTimelineIntroNotice = timelineStylePickerNotice(SessionChatTimelineStyle.ActivityCard)) }
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
                if (_state.value.chipTimelineIntroVisible || isBlocked()) return@launch
                val options =
                    SessionChatTimelineStyleOption.pickerOptions(
                        available = SessionChatDisplayPreferences.availableTimelineStyles,
                        current = SessionChatDisplayPreferences.timelineStyle,
                    )
                _state.update { it.copy(timelineStylePickerOptions = options, timelineStylePickerVisible = true) }
                onChange()
            }
    }

    /** Offers the intro once a long turn goes busy → idle and stays settled. */
    fun updateTurnActive(isTurnActive: Boolean) {
        val previous = chipTimelineIntroTurnActive
        chipTimelineIntroTurnActive = isTurnActive
        if (isTurnActive) {
            chipTimelineIntroJob?.cancel()
            return
        }
        if (previous != true || _state.value.chipTimelineIntroVisible) return
        val toolCalls = ChipTimelineIntro.lastTurnToolCalls(loadedMessages())
        if (!SessionChatDisplayPreferences.shouldOfferChipTimelineIntro(toolCalls, nowMs())) return
        chipTimelineIntroJob?.cancel()
        chipTimelineIntroJob =
            scope.launch {
                delay(ChipTimelineIntro.SETTLE_DELAY_MILLIS)
                if (isSessionBusy() || isBlocked()) return@launch
                if (!SessionChatDisplayPreferences.shouldOfferChipTimelineIntro(toolCalls, nowMs())) return@launch
                _state.update { it.copy(chipTimelineIntroVisible = true) }
                onChange()
            }
    }

    /** A new session or draft: the intro starts over; the picker stays decided for this ViewModel. */
    fun reset() {
        chipTimelineIntroJob?.cancel()
        chipTimelineIntroJob = null
        _state.update { it.copy(chipTimelineIntroVisible = false, chipTimelineIntroNotice = null) }
        chipTimelineIntroTurnActive = null
    }

    /** The snackbar was dismissed. */
    fun clearNotices() {
        _state.update { it.copy(chipTimelineIntroNotice = null) }
    }

    private fun hideChipTimelineIntro(): Boolean {
        if (!_state.value.chipTimelineIntroVisible) return false
        _state.update { it.copy(chipTimelineIntroVisible = false) }
        return true
    }

    companion object {
        const val CHIP_TIMELINE_INTRO_ENABLED_NOTICE = "Chip timeline is on. Turn it off in Settings."

        /** Pause after the first history load before the Timeline Style picker rises (#805). */
        const val TIMELINE_STYLE_PICKER_DELAY_MILLIS = 600L

        fun timelineStylePickerNotice(style: SessionChatTimelineStyle): String =
            "Timeline: ${style.title}. Change it any time in Settings."
    }
}
