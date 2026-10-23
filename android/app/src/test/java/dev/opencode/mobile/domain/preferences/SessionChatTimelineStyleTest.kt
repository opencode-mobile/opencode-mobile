package dev.opencode.mobile.domain.preferences

import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineStyleOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatTimelineStyleTest {
    private val store = InMemoryPreferences()

    @After
    fun tearDown() {
        SessionChatDisplayPreferences.initialize(InMemoryPreferences())
        SessionChatDisplayPreferences.clear()
    }

    private fun launch(existingInstall: Boolean) {
        SessionChatDisplayPreferences.initialize(store)
        SessionChatDisplayPreferences.resolveTimelineForInstall(existingInstall)
    }

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
    }

    @Test
    fun resolve_newInstall_startsOnActivityCardWithPickerDone() {
        launch(existingInstall = false)

        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("activity_card", store.values["timeline_style"])
        assertEquals(true, store.values["timeline_style_picker_done"])
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_existingInstallWithoutStyle_keepsStandardWithPickerPending() {
        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("standard", store.values["timeline_style"])
        assertEquals(false, store.values["timeline_style_picker_done"])
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_existingInstallWithStoredChips_keepsChipsWithPickerPending() {
        store.values["timeline_style"] = "chips"

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Chips, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("chips", store.values["timeline_style"])
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_secondLaunch_isNoOp() {
        launch(existingInstall = true)
        SessionChatDisplayPreferences.updateTimelineStyle(SessionChatTimelineStyle.StepRail)
        val stored = store.values.toMap()

        SessionChatDisplayPreferences.initialize(InMemoryPreferences())
        launch(existingInstall = true)

        assertEquals(stored, store.values)
        assertEquals(SessionChatTimelineStyle.StepRail, SessionChatDisplayPreferences.timelineStylePreference)
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_freshInstallAnsweredOnce_neverBecomesPendingLater() {
        launch(existingInstall = false)

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatDisplayPreferences.timelineStylePreference)
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_legacyChipKey_stillMigrates() {
        store.values["chip_timeline"] = true

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Chips, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("chips", store.values["timeline_style"])
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun updateTimelineStyle_endsPicker() {
        launch(existingInstall = true)

        SessionChatDisplayPreferences.updateTimelineStyle(SessionChatTimelineStyle.StepRail)

        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
        assertEquals(true, store.values["timeline_style_picker_done"])
        assertEquals("step_rail", store.values["timeline_style"])
        assertTrue(SessionChatDisplayPreferences.chipTimelineIntroDone)
    }

    @Test
    fun dismissPicker_storesActivityCard() {
        launch(existingInstall = true)

        SessionChatDisplayPreferences.dismissTimelineStylePicker()

        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("activity_card", store.values["timeline_style"])
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun clear_resetsPickerKey() {
        launch(existingInstall = true)

        SessionChatDisplayPreferences.clear()

        assertFalse(store.values.containsKey("timeline_style_picker_done"))
        assertNull(SessionChatDisplayPreferences.timelineStylePickerDone)
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun chipIntro_notOfferedWhilePickerPending() {
        launch(existingInstall = true)
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)

        assertFalse(
            SessionChatDisplayPreferences.shouldOfferChipTimelineIntro(
                turnToolCalls = ChipTimelineIntro.MIN_TOOL_CALLS,
                nowMillis = 0L,
            ),
        )
    }

    @Test
    fun pickerOptions_activityCardFirstThenUsualOrderWithTags() {
        val options =
            SessionChatTimelineStyleOption.pickerOptions(
                available = SessionChatTimelineStyle.available(chipTimelineAvailable = true),
                current = SessionChatTimelineStyle.Chips,
            )

        assertEquals(
            listOf(
                SessionChatTimelineStyle.ActivityCard,
                SessionChatTimelineStyle.Standard,
                SessionChatTimelineStyle.Chips,
                SessionChatTimelineStyle.StepRail,
            ),
            options.map { it.style },
        )
        assertEquals(listOf("Recommended", null, "Current", null), options.map { it.tag })
    }

    @Test
    fun pickerOptions_currentActivityCard_onlyRecommended() {
        val options =
            SessionChatTimelineStyleOption.pickerOptions(
                available = SessionChatTimelineStyle.available(chipTimelineAvailable = false),
                current = SessionChatTimelineStyle.ActivityCard,
            )

        assertEquals(
            listOf(SessionChatTimelineStyle.ActivityCard, SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.StepRail),
            options.map { it.style },
        )
        assertEquals(listOf("Recommended", null, null), options.map { it.tag })
    }

    @Test
    fun available_hidesChipsWhileFlagIsOff() {
        assertEquals(
            listOf(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.StepRail, SessionChatTimelineStyle.ActivityCard),
            SessionChatTimelineStyle.available(chipTimelineAvailable = false),
        )
        assertEquals(SessionChatTimelineStyle.entries, SessionChatTimelineStyle.available(chipTimelineAvailable = true))
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
