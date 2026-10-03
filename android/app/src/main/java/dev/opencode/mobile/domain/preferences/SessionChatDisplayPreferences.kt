package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.domain.featureflags.FeatureFlag
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.message.FollowUpDelivery

/**
 * Session Chat display preferences (OPE-144).
 * Device-local, default on, cleared by Clear All Data.
 */
object SessionChatDisplayPreferences {
    private const val PREFS_NAME = "opencode_session_chat_display"
    private const val KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS = "collapse_consecutive_tool_calls"
    private const val KEY_SMOOTH_STREAMING_UPDATES = "smooth_streaming_updates"
    private const val KEY_CHIP_TIMELINE = "chip_timeline"
    private const val KEY_FOLLOW_UP_DELIVERY = "follow_up_delivery"
    private const val KEY_CHIP_TIMELINE_INTRO_DONE = "chip_timeline_intro_done"
    private const val KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL = "chip_timeline_intro_snoozed_until"
    const val DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS = true
    const val DEFAULT_SMOOTH_STREAMING_UPDATES = true
    const val DEFAULT_CHIP_TIMELINE = false
    val DEFAULT_FOLLOW_UP_DELIVERY = FollowUpDelivery.Steer

    /** When true, a run of two or more consecutive visible tool parts in one
     * assistant message collapses into one summary tool group. */
    var collapseConsecutiveToolCalls: Boolean by mutableStateOf(DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
        private set

    /** When true, live assistant tokens coalesce into 80ms / 160ms display frames.
     * Off paints every streaming snapshot immediately. */
    var smoothStreamingUpdates: Boolean by mutableStateOf(DEFAULT_SMOOTH_STREAMING_UPDATES)
        private set

    var followUpDelivery: FollowUpDelivery by mutableStateOf(DEFAULT_FOLLOW_UP_DELIVERY)
        private set

    /** Stored "Chip timeline" preference, independent of the remote flag. */
    var chipTimelinePreference: Boolean by mutableStateOf(DEFAULT_CHIP_TIMELINE)
        private set

    /** True once the Chip timeline intro sheet has been answered for good (OPE-270). */
    var chipTimelineIntroDone: Boolean by mutableStateOf(false)
        private set

    /** Epoch millis until which a Later answer hides the intro sheet, or null if never snoozed. */
    var chipTimelineIntroSnoozedUntilMillis: Long? by mutableStateOf(null)
        private set

    /** True while the `session_chat_chip_timeline` flag allows the mode at all (OPE-269). */
    val chipTimelineAvailable: Boolean
        get() = FeatureFlags.isEnabled(FeatureFlag.SessionChatChipTimeline)

    /**
     * When true, Session Chat shows the tools an agent used as chips under its
     * narration (OPE-260). Effective value: the stored preference gated by
     * [chipTimelineAvailable]; a remotely disabled flag renders the standard
     * timeline regardless of the stored preference.
     */
    val chipTimeline: Boolean
        get() = chipTimelinePreference && chipTimelineAvailable

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        collapseConsecutiveToolCalls =
            if (prefs.contains(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)) {
                prefs.getBoolean(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS, DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            } else {
                DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
            }
        smoothStreamingUpdates =
            if (prefs.contains(KEY_SMOOTH_STREAMING_UPDATES)) {
                prefs.getBoolean(KEY_SMOOTH_STREAMING_UPDATES, DEFAULT_SMOOTH_STREAMING_UPDATES)
            } else {
                DEFAULT_SMOOTH_STREAMING_UPDATES
            }
        followUpDelivery =
            FollowUpDelivery.fromWire(prefs.getString(KEY_FOLLOW_UP_DELIVERY, null))
                ?: DEFAULT_FOLLOW_UP_DELIVERY
        chipTimelinePreference = prefs.getBoolean(KEY_CHIP_TIMELINE, DEFAULT_CHIP_TIMELINE)
        chipTimelineIntroDone = prefs.getBoolean(KEY_CHIP_TIMELINE_INTRO_DONE, false)
        chipTimelineIntroSnoozedUntilMillis =
            if (prefs.contains(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL)) {
                prefs.getLong(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL, 0L)
            } else {
                null
            }
    }

    fun updateCollapseConsecutiveToolCalls(value: Boolean) {
        collapseConsecutiveToolCalls = value
        prefs()?.edit()?.putBoolean(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS, value)?.apply()
    }

    fun updateSmoothStreamingUpdates(value: Boolean) {
        smoothStreamingUpdates = value
        prefs()?.edit()?.putBoolean(KEY_SMOOTH_STREAMING_UPDATES, value)?.apply()
    }

    fun updateFollowUpDelivery(value: FollowUpDelivery) {
        followUpDelivery = value
        prefs()?.edit()?.putString(KEY_FOLLOW_UP_DELIVERY, value.wireValue)?.apply()
    }

    /** Any explicit choice, from Settings or the intro sheet, also ends the intro. */
    fun updateChipTimeline(value: Boolean) {
        chipTimelinePreference = value
        chipTimelineIntroDone = true
        prefs()
            ?.edit()
            ?.putBoolean(KEY_CHIP_TIMELINE, value)
            ?.putBoolean(KEY_CHIP_TIMELINE_INTRO_DONE, true)
            ?.apply()
    }

    /** Intro sheet "Don't show again", or a second Later. */
    fun finishChipTimelineIntro() {
        chipTimelineIntroDone = true
        prefs()?.edit()?.putBoolean(KEY_CHIP_TIMELINE_INTRO_DONE, true)?.apply()
    }

    /** Intro sheet Later: snooze the first time, end the intro the second time. */
    fun postponeChipTimelineIntro(nowMillis: Long) {
        val until = ChipTimelineIntro.laterSnoozeUntil(chipTimelineIntroSnoozedUntilMillis, nowMillis)
        if (until == null) {
            finishChipTimelineIntro()
            return
        }
        chipTimelineIntroSnoozedUntilMillis = until
        prefs()?.edit()?.putLong(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL, until)?.apply()
    }

    /** Whether a finished turn with [turnToolCalls] tool calls should offer the intro sheet. */
    fun shouldOfferChipTimelineIntro(
        turnToolCalls: Int,
        nowMillis: Long,
    ): Boolean =
        ChipTimelineIntro.shouldShow(
            chipTimelineAvailable = chipTimelineAvailable,
            chipTimelineOn = chipTimelinePreference,
            introDone = chipTimelineIntroDone,
            snoozedUntilMillis = chipTimelineIntroSnoozedUntilMillis,
            nowMillis = nowMillis,
            turnToolCalls = turnToolCalls,
        )

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            ?.remove(KEY_SMOOTH_STREAMING_UPDATES)
            ?.remove(KEY_FOLLOW_UP_DELIVERY)
            ?.remove(KEY_CHIP_TIMELINE)
            ?.remove(KEY_CHIP_TIMELINE_INTRO_DONE)
            ?.remove(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL)
            ?.apply()
        collapseConsecutiveToolCalls = DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
        smoothStreamingUpdates = DEFAULT_SMOOTH_STREAMING_UPDATES
        followUpDelivery = DEFAULT_FOLLOW_UP_DELIVERY
        chipTimelinePreference = DEFAULT_CHIP_TIMELINE
        chipTimelineIntroDone = false
        chipTimelineIntroSnoozedUntilMillis = null
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
