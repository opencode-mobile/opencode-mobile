package dev.opencode.mobile.domain.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.domain.featureflags.FeatureFlag
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.message.FollowUpDelivery

/**
 * How Session Chat lays out an assistant turn (#779): tool cards and groups,
 * tools as chips under the narration, one line per step on a rail, or one
 * live activity card per run of steps.
 */
enum class SessionChatTimelineStyle(
    val wireValue: String,
    val title: String,
    val subtitle: String,
) {
    Standard("standard", "Standard", "Tool cards and groups between the agent's messages"),
    Chips("chips", "Chips", "Tools as chips under the agent's narration"),
    StepRail("step_rail", "Step Rail", "One line per step on a rail, live while the agent works"),
    ActivityCard("activity_card", "Activity Card", "Running steps on one live card that folds into a summary"),
    ;

    companion object {
        /** In-memory style before an install is resolved and after Clear All Data. */
        val DEFAULT = Standard

        fun fromWire(value: String?): SessionChatTimelineStyle? = entries.firstOrNull { it.wireValue == value }

        /**
         * Stored style, migrating the old `chip_timeline` boolean when no style
         * was stored yet (true is Chips, false is Standard). Null when neither
         * is stored, so the install default can decide.
         */
        fun resolveStored(
            storedStyle: String?,
            storedChipTimeline: Boolean?,
        ): SessionChatTimelineStyle? =
            fromWire(storedStyle)
                ?: storedChipTimeline?.let { if (it) Chips else Standard }

        /** Styles a picker offers, in order; Chips only while its flag is on. */
        fun available(chipTimelineAvailable: Boolean): List<SessionChatTimelineStyle> =
            entries.filter { it != Chips || chipTimelineAvailable }

        /** Chips is gated by the remote flag; a disabled flag renders Standard. */
        fun effective(
            stored: SessionChatTimelineStyle,
            chipTimelineAvailable: Boolean,
        ): SessionChatTimelineStyle = if (stored == Chips && !chipTimelineAvailable) Standard else stored
    }
}

/**
 * Session Chat display preferences (OPE-144).
 * Device-local, default on, cleared by Clear All Data.
 */
object SessionChatDisplayPreferences {
    private const val PREFS_NAME = "opencode_session_chat_display"
    private const val KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS = "collapse_consecutive_tool_calls"
    private const val KEY_CHIP_TIMELINE = "chip_timeline"
    private const val KEY_TIMELINE_STYLE = "timeline_style"
    private const val KEY_FOLLOW_UP_DELIVERY = "follow_up_delivery"
    private const val KEY_CHIP_TIMELINE_INTRO_DONE = "chip_timeline_intro_done"
    private const val KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL = "chip_timeline_intro_snoozed_until"
    private const val KEY_TIMELINE_STYLE_PICKER_DONE = "timeline_style_picker_done"
    const val DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS = true
    const val DEFAULT_CHIP_TIMELINE = false
    val DEFAULT_FOLLOW_UP_DELIVERY = FollowUpDelivery.Steer

    /** When true, a run of two or more consecutive visible tool parts in one
     * assistant message collapses into one summary tool group. */
    var collapseConsecutiveToolCalls: Boolean by mutableStateOf(DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
        private set

    var followUpDelivery: FollowUpDelivery by mutableStateOf(DEFAULT_FOLLOW_UP_DELIVERY)
        private set

    /** Stored Timeline Style, independent of the remote flag (#779). */
    var timelineStylePreference: SessionChatTimelineStyle by mutableStateOf(SessionChatTimelineStyle.DEFAULT)
        private set

    /** Stored style is Chips, independent of the remote flag. */
    val chipTimelinePreference: Boolean
        get() = timelineStylePreference == SessionChatTimelineStyle.Chips

    /** True once the Chip timeline intro sheet has been answered for good (OPE-270). */
    var chipTimelineIntroDone: Boolean by mutableStateOf(false)
        private set

    /** Epoch millis until which a Later answer hides the intro sheet, or null if never snoozed. */
    var chipTimelineIntroSnoozedUntilMillis: Long? by mutableStateOf(null)
        private set

    /**
     * One-time Timeline Style picker (#805): false while an existing install still has to
     * answer it, true once answered (or on a fresh install), null when never settled.
     */
    var timelineStylePickerDone: Boolean? by mutableStateOf(null)
        private set

    /** Session Chat should offer the Timeline Style picker: the stored key is exactly false. */
    val timelineStylePickerPending: Boolean
        get() = timelineStylePickerDone == false

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
        get() = timelineStyle == SessionChatTimelineStyle.Chips

    /** Styles the Settings and Session Chat pickers offer. */
    val availableTimelineStyles: List<SessionChatTimelineStyle>
        get() = SessionChatTimelineStyle.available(chipTimelineAvailable)

    /** Effective Timeline Style: Chips falls back to Standard while the flag is off. */
    val timelineStyle: SessionChatTimelineStyle
        get() = SessionChatTimelineStyle.effective(timelineStylePreference, chipTimelineAvailable)

    /** Session Chat shows each step as one line on a rail (#779). Not flag-gated. */
    val stepRailTimeline: Boolean
        get() = timelineStyle == SessionChatTimelineStyle.StepRail

    /** Session Chat shows each run of steps as one activity card (#779). Not flag-gated. */
    val activityCardTimeline: Boolean
        get() = timelineStyle == SessionChatTimelineStyle.ActivityCard

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        initialize(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    internal fun initialize(prefs: SharedPreferences) {
        preferences = prefs
        collapseConsecutiveToolCalls =
            if (prefs.contains(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)) {
                prefs.getBoolean(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS, DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            } else {
                DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
            }
        followUpDelivery =
            FollowUpDelivery.fromWire(prefs.getString(KEY_FOLLOW_UP_DELIVERY, null))
                ?: DEFAULT_FOLLOW_UP_DELIVERY
        val storedStyle =
            SessionChatTimelineStyle.resolveStored(
                storedStyle = prefs.getString(KEY_TIMELINE_STYLE, null),
                storedChipTimeline =
                    if (prefs.contains(KEY_CHIP_TIMELINE)) prefs.getBoolean(KEY_CHIP_TIMELINE, DEFAULT_CHIP_TIMELINE) else null,
            )
        if (storedStyle != null && !prefs.contains(KEY_TIMELINE_STYLE)) {
            prefs.edit().putString(KEY_TIMELINE_STYLE, storedStyle.wireValue).apply()
        }
        timelineStylePreference = storedStyle ?: SessionChatTimelineStyle.DEFAULT
        chipTimelineIntroDone = prefs.getBoolean(KEY_CHIP_TIMELINE_INTRO_DONE, false)
        chipTimelineIntroSnoozedUntilMillis =
            if (prefs.contains(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL)) {
                prefs.getLong(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL, 0L)
            } else {
                null
            }
        timelineStylePickerDone =
            if (prefs.contains(KEY_TIMELINE_STYLE_PICKER_DONE)) {
                prefs.getBoolean(KEY_TIMELINE_STYLE_PICKER_DONE, true)
            } else {
                null
            }
    }

    fun updateCollapseConsecutiveToolCalls(value: Boolean) {
        collapseConsecutiveToolCalls = value
        prefs()?.edit()?.putBoolean(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS, value)?.apply()
    }

    fun updateFollowUpDelivery(value: FollowUpDelivery) {
        followUpDelivery = value
        prefs()?.edit()?.putString(KEY_FOLLOW_UP_DELIVERY, value.wireValue)?.apply()
    }

    /** Intro sheet shim: true is Chips, false is Standard. Also ends the intro. */
    fun updateChipTimeline(value: Boolean) {
        updateTimelineStyle(if (value) SessionChatTimelineStyle.Chips else SessionChatTimelineStyle.Standard)
    }

    /**
     * Any explicit choice, from Settings, the Chip intro, or the Timeline Style picker, also
     * ends the Chip intro and the picker.
     */
    fun updateTimelineStyle(value: SessionChatTimelineStyle) {
        timelineStylePreference = value
        chipTimelineIntroDone = true
        timelineStylePickerDone = true
        prefs()
            ?.edit()
            ?.putString(KEY_TIMELINE_STYLE, value.wireValue)
            ?.putBoolean(KEY_CHIP_TIMELINE_INTRO_DONE, true)
            ?.putBoolean(KEY_TIMELINE_STYLE_PICKER_DONE, true)
            ?.apply()
    }

    /** Timeline Style picker swipe, scrim, back, or close: keeps the recommended Activity Card. */
    fun dismissTimelineStylePicker() {
        updateTimelineStyle(SessionChatTimelineStyle.ActivityCard)
    }

    /**
     * Settles the starting Timeline Style and the one-time picker once per install (#805).
     * [existingInstall] is true when the app opens past App onboarding. A fresh install starts
     * on Activity Card and never sees the picker; an existing install with nothing stored keeps
     * Standard and is offered the picker. A stored value, from an earlier launch or a Settings
     * choice, always wins.
     */
    fun resolveTimelineForInstall(existingInstall: Boolean) {
        val prefs = prefs() ?: return
        val editor = prefs.edit()
        if (!prefs.contains(KEY_TIMELINE_STYLE) && !prefs.contains(KEY_CHIP_TIMELINE)) {
            val value = if (existingInstall) SessionChatTimelineStyle.Standard else SessionChatTimelineStyle.ActivityCard
            timelineStylePreference = value
            editor.putString(KEY_TIMELINE_STYLE, value.wireValue)
        }
        if (!prefs.contains(KEY_TIMELINE_STYLE_PICKER_DONE)) {
            val done = !existingInstall
            timelineStylePickerDone = done
            editor.putBoolean(KEY_TIMELINE_STYLE_PICKER_DONE, done)
        }
        editor.apply()
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

    /**
     * Whether a finished turn with [turnToolCalls] tool calls should offer the intro sheet.
     * Never while the Timeline Style picker is still pending (#805).
     */
    fun shouldOfferChipTimelineIntro(
        turnToolCalls: Int,
        nowMillis: Long,
    ): Boolean =
        !timelineStylePickerPending &&
            ChipTimelineIntro.shouldShow(
                chipTimelineAvailable = chipTimelineAvailable,
                chipTimelineOn = chipTimelinePreference,
                timelineStyleChosen =
                    timelineStylePreference == SessionChatTimelineStyle.StepRail ||
                        timelineStylePreference == SessionChatTimelineStyle.ActivityCard,
                introDone = chipTimelineIntroDone,
                snoozedUntilMillis = chipTimelineIntroSnoozedUntilMillis,
                nowMillis = nowMillis,
                turnToolCalls = turnToolCalls,
            )

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            ?.remove(KEY_FOLLOW_UP_DELIVERY)
            ?.remove(KEY_CHIP_TIMELINE)
            ?.remove(KEY_TIMELINE_STYLE)
            ?.remove(KEY_CHIP_TIMELINE_INTRO_DONE)
            ?.remove(KEY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL)
            ?.remove(KEY_TIMELINE_STYLE_PICKER_DONE)
            ?.apply()
        collapseConsecutiveToolCalls = DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
        followUpDelivery = DEFAULT_FOLLOW_UP_DELIVERY
        timelineStylePreference = SessionChatTimelineStyle.DEFAULT
        chipTimelineIntroDone = false
        chipTimelineIntroSnoozedUntilMillis = null
        timelineStylePickerDone = null
    }

    private fun prefs() = preferences
}
