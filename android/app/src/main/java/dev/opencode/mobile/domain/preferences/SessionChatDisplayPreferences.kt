package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.domain.featureflags.FeatureFlag
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.domain.message.FollowUpDelivery

/**
 * How Session Chat lays out an assistant turn (#779): tool cards and groups,
 * tools as chips under the narration, or one line per step on a rail.
 */
enum class SessionChatTimelineStyle(
    val wireValue: String,
) {
    Standard("standard"),
    Chips("chips"),
    StepRail("step_rail"),
    ;

    companion object {
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

        /** Starting style when nothing is stored: a fresh install starts on Chips. */
        fun forInstall(existingInstall: Boolean): SessionChatTimelineStyle =
            if (ChipTimelineIntro.startingChipTimeline(existingInstall)) Chips else Standard

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

    /** Effective Timeline Style: Chips falls back to Standard while the flag is off. */
    val timelineStyle: SessionChatTimelineStyle
        get() = SessionChatTimelineStyle.effective(timelineStylePreference, chipTimelineAvailable)

    /** Session Chat shows each step as one line on a rail (#779). Not flag-gated. */
    val stepRailTimeline: Boolean
        get() = timelineStyle == SessionChatTimelineStyle.StepRail

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

    /** Any explicit choice, from Settings or the intro sheet, also ends the intro. */
    fun updateTimelineStyle(value: SessionChatTimelineStyle) {
        timelineStylePreference = value
        chipTimelineIntroDone = true
        prefs()
            ?.edit()
            ?.putString(KEY_TIMELINE_STYLE, value.wireValue)
            ?.putBoolean(KEY_CHIP_TIMELINE_INTRO_DONE, true)
            ?.apply()
    }

    /**
     * Settles the starting Chip timeline value once per install (OPE-270).
     * [existingInstall] is true when the app opens past App onboarding. A
     * stored value, from an earlier launch or a Settings choice, always wins.
     */
    fun resolveChipTimelineForInstall(existingInstall: Boolean) {
        val prefs = prefs() ?: return
        if (prefs.contains(KEY_TIMELINE_STYLE) || prefs.contains(KEY_CHIP_TIMELINE)) return
        val value = SessionChatTimelineStyle.forInstall(existingInstall)
        timelineStylePreference = value
        prefs.edit().putString(KEY_TIMELINE_STYLE, value.wireValue).apply()
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
            timelineStyleChosen = timelineStylePreference == SessionChatTimelineStyle.StepRail,
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
            ?.apply()
        collapseConsecutiveToolCalls = DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
        followUpDelivery = DEFAULT_FOLLOW_UP_DELIVERY
        timelineStylePreference = SessionChatTimelineStyle.DEFAULT
        chipTimelineIntroDone = false
        chipTimelineIntroSnoozedUntilMillis = null
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
