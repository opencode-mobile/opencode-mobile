package dev.opencode.mobile.domain.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.domain.message.FollowUpDelivery

/**
 * How Session Chat lays out an assistant turn (#779): tool cards and groups,
 * one line per step on a rail, or one live activity card per run of steps.
 */
/** Names and descriptions are app text, keyed in the UI (`settings_timeline_style_*`). */
enum class SessionChatTimelineStyle(
    val wireValue: String,
) {
    Standard("standard"),
    StepRail("step_rail"),
    ActivityCard("activity_card"),
    ;

    companion object {
        /** In-memory style before an install is resolved and after Clear All Data. */
        val DEFAULT = Standard

        /** Wire value of the removed Chips style (#1120); a stored one reads as [Standard]. */
        internal const val REMOVED_CHIPS_WIRE_VALUE = "chips"

        fun fromWire(value: String?): SessionChatTimelineStyle? =
            if (value == REMOVED_CHIPS_WIRE_VALUE) Standard else entries.firstOrNull { it.wireValue == value }

        /**
         * Stored style, migrating the old `chip_timeline` boolean when no style was stored yet.
         * The Chips style is gone (#1120), so a stored `chips` and either legacy boolean value
         * resolve to Standard. Null when neither is stored, so the install default can decide.
         */
        fun resolveStored(
            storedStyle: String?,
            storedChipTimeline: Boolean?,
        ): SessionChatTimelineStyle? =
            fromWire(storedStyle)
                ?: storedChipTimeline?.let { Standard }
    }
}

/**
 * Session Chat display preferences (OPE-144).
 * Device-local, default on, cleared by Clear All Data.
 */
object SessionChatDisplayPreferences {
    private const val PREFS_NAME = "opencode_session_chat_display"
    private const val KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS = "collapse_consecutive_tool_calls"
    private const val KEY_TIMELINE_STYLE = "timeline_style"
    private const val KEY_FOLLOW_UP_DELIVERY = "follow_up_delivery"
    private const val KEY_TIMELINE_STYLE_PICKER_DONE = "timeline_style_picker_done"

    // Keys left by the removed Chips style (#1120): migrated on launch, removed by Clear All Data.
    private const val KEY_LEGACY_CHIP_TIMELINE = "chip_timeline"
    private const val KEY_LEGACY_CHIP_TIMELINE_INTRO_DONE = "chip_timeline_intro_done"
    private const val KEY_LEGACY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL = "chip_timeline_intro_snoozed_until"
    const val DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS = true
    val DEFAULT_FOLLOW_UP_DELIVERY = FollowUpDelivery.Steer

    /** When true, a run of two or more consecutive visible tool parts in one
     * assistant message collapses into one summary tool group. */
    var collapseConsecutiveToolCalls: Boolean by mutableStateOf(DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
        private set

    var followUpDelivery: FollowUpDelivery by mutableStateOf(DEFAULT_FOLLOW_UP_DELIVERY)
        private set

    /** Stored Timeline Style (#779). */
    var timelineStylePreference: SessionChatTimelineStyle by mutableStateOf(SessionChatTimelineStyle.DEFAULT)
        private set

    /**
     * One-time Timeline Style picker (#805): false while an existing install still has to
     * answer it, true once answered (or on a fresh install), null when never settled.
     */
    var timelineStylePickerDone: Boolean? by mutableStateOf(null)
        private set

    /** Session Chat should offer the Timeline Style picker: the stored key is exactly false. */
    val timelineStylePickerPending: Boolean
        get() = !isSandboxed && timelineStylePickerDone == false

    /** Styles the Settings and Session Chat pickers offer. */
    val availableTimelineStyles: List<SessionChatTimelineStyle>
        get() = SessionChatTimelineStyle.entries

    /** Timeline Style Session Chat renders. */
    val timelineStyle: SessionChatTimelineStyle
        get() = timelineStylePreference

    /** Session Chat shows each step as one line on a rail (#779). */
    val stepRailTimeline: Boolean
        get() = timelineStyle == SessionChatTimelineStyle.StepRail

    /** Session Chat shows each run of steps as one activity card (#779). */
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
        val storedWire = prefs.getString(KEY_TIMELINE_STYLE, null)
        val storedStyle =
            SessionChatTimelineStyle.resolveStored(
                storedStyle = storedWire,
                storedChipTimeline =
                    if (prefs.contains(KEY_LEGACY_CHIP_TIMELINE)) prefs.getBoolean(KEY_LEGACY_CHIP_TIMELINE, false) else null,
            )
        // Rewrite a migrated value (legacy boolean, or the removed `chips`) as a real style.
        if (storedStyle != null && storedWire != storedStyle.wireValue) {
            prefs.edit().putString(KEY_TIMELINE_STYLE, storedStyle.wireValue).apply()
        }
        timelineStylePreference = storedStyle ?: SessionChatTimelineStyle.DEFAULT
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

    /** Any explicit choice, from Settings or the Timeline Style picker, also ends the picker. */
    fun updateTimelineStyle(value: SessionChatTimelineStyle) {
        timelineStylePreference = value
        timelineStylePickerDone = true
        prefs()
            ?.edit()
            ?.putString(KEY_TIMELINE_STYLE, value.wireValue)
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
        // A stored legacy `chip_timeline` also marks an existing choice; [initialize] migrates it.
        if (!prefs.contains(KEY_TIMELINE_STYLE) && !prefs.contains(KEY_LEGACY_CHIP_TIMELINE)) {
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

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            ?.remove(KEY_FOLLOW_UP_DELIVERY)
            ?.remove(KEY_TIMELINE_STYLE)
            ?.remove(KEY_TIMELINE_STYLE_PICKER_DONE)
            ?.remove(KEY_LEGACY_CHIP_TIMELINE)
            ?.remove(KEY_LEGACY_CHIP_TIMELINE_INTRO_DONE)
            ?.remove(KEY_LEGACY_CHIP_TIMELINE_INTRO_SNOOZED_UNTIL)
            ?.apply()
        collapseConsecutiveToolCalls = DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
        followUpDelivery = DEFAULT_FOLLOW_UP_DELIVERY
        timelineStylePreference = SessionChatTimelineStyle.DEFAULT
        timelineStylePickerDone = null
    }

    private data class Snapshot(
        val collapseConsecutiveToolCalls: Boolean,
        val followUpDelivery: FollowUpDelivery,
        val timelineStylePreference: SessionChatTimelineStyle,
        val timelineStylePickerDone: Boolean?,
    )

    private var sandboxDepth = 0
    private var sandboxSnapshot: Snapshot? = null

    /** True while a [sandbox] handle is open. */
    val isSandboxed: Boolean
        get() = sandboxDepth > 0

    /**
     * Keeps every change in memory until the returned handle closes, then restores the values
     * from before. While a sandbox is open the one-time Timeline Style picker is not
     * offered. The Session Chat Playground holds one: nothing it does may change the real
     * preferences.
     */
    fun sandbox(): AutoCloseable {
        if (sandboxDepth == 0) {
            sandboxSnapshot =
                Snapshot(
                    collapseConsecutiveToolCalls = collapseConsecutiveToolCalls,
                    followUpDelivery = followUpDelivery,
                    timelineStylePreference = timelineStylePreference,
                    timelineStylePickerDone = timelineStylePickerDone,
                )
        }
        sandboxDepth += 1
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                endSandbox()
            }
        }
    }

    private fun endSandbox() {
        sandboxDepth -= 1
        if (sandboxDepth > 0) return
        val snapshot = sandboxSnapshot ?: return
        sandboxSnapshot = null
        collapseConsecutiveToolCalls = snapshot.collapseConsecutiveToolCalls
        followUpDelivery = snapshot.followUpDelivery
        timelineStylePreference = snapshot.timelineStylePreference
        timelineStylePickerDone = snapshot.timelineStylePickerDone
    }

    /** Where writes go; null while sandboxed, so changes stay in memory. */
    private fun prefs() = preferences.takeIf { !isSandboxed }
}
