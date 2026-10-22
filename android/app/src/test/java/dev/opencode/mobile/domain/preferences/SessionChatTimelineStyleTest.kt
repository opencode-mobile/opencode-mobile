package dev.opencode.mobile.domain.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChatTimelineStyleTest {
    @Test
    fun migration_chipTimelineTrue_isChips() {
        assertEquals(SessionChatTimelineStyle.Chips, SessionChatTimelineStyle.resolveStored(null, storedChipTimeline = true))
    }

    @Test
    fun migration_chipTimelineFalse_isStandard() {
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.resolveStored(null, storedChipTimeline = false))
    }

    @Test
    fun storedActivityCard_winsOverOldChipKey() {
        assertEquals(
            SessionChatTimelineStyle.ActivityCard,
            SessionChatTimelineStyle.resolveStored("activity_card", storedChipTimeline = true),
        )
    }

    @Test
    fun storedStyle_winsOverOldChipKey() {
        assertEquals(SessionChatTimelineStyle.StepRail, SessionChatTimelineStyle.resolveStored("step_rail", storedChipTimeline = true))
    }

    @Test
    fun missing_leavesInstallDefaultToDecide() {
        assertNull(SessionChatTimelineStyle.resolveStored(null, storedChipTimeline = null))
        assertEquals(SessionChatTimelineStyle.Chips, SessionChatTimelineStyle.forInstall(existingInstall = false))
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.forInstall(existingInstall = true))
    }

    @Test
    fun wireValues_roundTrip() {
        SessionChatTimelineStyle.entries.forEach { assertEquals(it, SessionChatTimelineStyle.fromWire(it.wireValue)) }
        assertEquals(listOf("standard", "chips", "step_rail", "activity_card"), SessionChatTimelineStyle.entries.map { it.wireValue })
        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatTimelineStyle.fromWire("activity_card"))
    }

    @Test
    fun flagOff_makesChipsEffectiveStandard() {
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.effective(SessionChatTimelineStyle.Chips, false))
        assertEquals(SessionChatTimelineStyle.Chips, SessionChatTimelineStyle.effective(SessionChatTimelineStyle.Chips, true))
        assertEquals(SessionChatTimelineStyle.StepRail, SessionChatTimelineStyle.effective(SessionChatTimelineStyle.StepRail, false))
        assertEquals(
            SessionChatTimelineStyle.ActivityCard,
            SessionChatTimelineStyle.effective(SessionChatTimelineStyle.ActivityCard, false),
        )
    }

    @Test
    fun clear_resetsToStandard() {
        SessionChatDisplayPreferences.clear()
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertFalse(SessionChatDisplayPreferences.stepRailTimeline)
        assertFalse(SessionChatDisplayPreferences.activityCardTimeline)
    }

    @Test
    fun intro_notOfferedOnceStepRailIsChosen() {
        assertFalse(
            ChipTimelineIntro.shouldShow(
                chipTimelineAvailable = true,
                chipTimelineOn = false,
                introDone = false,
                snoozedUntilMillis = null,
                nowMillis = 0L,
                turnToolCalls = ChipTimelineIntro.MIN_TOOL_CALLS,
                timelineStyleChosen = true,
            ),
        )
    }
}
