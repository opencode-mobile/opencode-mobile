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
    fun migration_chipTimelineTrue_isStandard() {
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.resolveStored(null, storedChipTimeline = true))
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
    fun migration_storedChips_isStandard() {
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.fromWire("chips"))
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.resolveStored("chips", storedChipTimeline = null))
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.resolveStored("chips", storedChipTimeline = true))
    }

    @Test
    fun unknownStoredStyle_fallsBackToOldChipKeyOrInstallDefault() {
        assertNull(SessionChatTimelineStyle.fromWire("unknown"))
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.resolveStored("unknown", storedChipTimeline = false))
        assertNull(SessionChatTimelineStyle.resolveStored("unknown", storedChipTimeline = null))
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
    fun resolve_existingInstallWithStoredChips_becomesStandardWithPickerPending() {
        store.values["timeline_style"] = "chips"

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("standard", store.values["timeline_style"])
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_storedChipsAfterPickerAnswered_staysStandardWithoutPicker() {
        store.values["timeline_style"] = "chips"
        store.values["timeline_style_picker_done"] = true

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("standard", store.values["timeline_style"])
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
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
    fun resolve_legacyChipKeyTrue_migratesToStandard() {
        store.values["chip_timeline"] = true

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("standard", store.values["timeline_style"])
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun resolve_legacyChipKeyFalse_migratesToStandard() {
        store.values["chip_timeline"] = false

        launch(existingInstall = true)

        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertEquals("standard", store.values["timeline_style"])
        assertTrue(SessionChatDisplayPreferences.timelineStylePickerPending)
    }

    @Test
    fun clear_removesLegacyChipKeys() {
        store.values["chip_timeline"] = true
        store.values["chip_timeline_intro_done"] = true
        store.values["chip_timeline_intro_snoozed_until"] = 5L
        launch(existingInstall = true)

        SessionChatDisplayPreferences.clear()

        assertFalse(store.values.containsKey("chip_timeline"))
        assertFalse(store.values.containsKey("chip_timeline_intro_done"))
        assertFalse(store.values.containsKey("chip_timeline_intro_snoozed_until"))
        assertFalse(store.values.containsKey("timeline_style"))
    }

    @Test
    fun updateTimelineStyle_endsPicker() {
        launch(existingInstall = true)

        SessionChatDisplayPreferences.updateTimelineStyle(SessionChatTimelineStyle.StepRail)

        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
        assertEquals(true, store.values["timeline_style_picker_done"])
        assertEquals("step_rail", store.values["timeline_style"])
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
    fun clearAllData_settlesAsFreshInstall() {
        launch(existingInstall = true)

        // Clear All Data, as SettingsViewModel.clearAllData runs it.
        SessionChatDisplayPreferences.clear()
        SessionChatDisplayPreferences.resolveTimelineForInstall(existingInstall = false)

        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatDisplayPreferences.timelineStylePreference)
        assertFalse(SessionChatDisplayPreferences.timelineStylePickerPending)
        // The next launch, already past onboarding with a profile, must not offer the picker.
        launch(existingInstall = true)
        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatDisplayPreferences.timelineStylePreference)
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
    fun pickerOptions_activityCardFirstThenUsualOrderWithTags() {
        val options =
            SessionChatTimelineStyleOption.pickerOptions(
                available = SessionChatTimelineStyle.entries,
                current = SessionChatTimelineStyle.Standard,
            )

        assertEquals(
            listOf(
                SessionChatTimelineStyle.ActivityCard,
                SessionChatTimelineStyle.Standard,
                SessionChatTimelineStyle.StepRail,
            ),
            options.map { it.style },
        )
        assertEquals(listOf("Recommended", "Current", null), options.map { it.tag })
    }

    @Test
    fun pickerOptions_currentActivityCard_onlyRecommended() {
        val options =
            SessionChatTimelineStyleOption.pickerOptions(
                available = SessionChatTimelineStyle.entries,
                current = SessionChatTimelineStyle.ActivityCard,
            )

        assertEquals(
            listOf(SessionChatTimelineStyle.ActivityCard, SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.StepRail),
            options.map { it.style },
        )
        assertEquals(listOf("Recommended", null, null), options.map { it.tag })
    }

    @Test
    fun entries_areStandardStepRailActivityCard() {
        assertEquals(
            listOf(SessionChatTimelineStyle.Standard, SessionChatTimelineStyle.StepRail, SessionChatTimelineStyle.ActivityCard),
            SessionChatTimelineStyle.entries,
        )
        assertEquals(SessionChatTimelineStyle.entries, SessionChatDisplayPreferences.availableTimelineStyles)
    }

    @Test
    fun wireValues_roundTrip() {
        SessionChatTimelineStyle.entries.forEach { assertEquals(it, SessionChatTimelineStyle.fromWire(it.wireValue)) }
        assertEquals(listOf("standard", "step_rail", "activity_card"), SessionChatTimelineStyle.entries.map { it.wireValue })
        assertEquals(SessionChatTimelineStyle.ActivityCard, SessionChatTimelineStyle.fromWire("activity_card"))
    }

    @Test
    fun clear_resetsToStandard() {
        SessionChatDisplayPreferences.clear()
        assertEquals(SessionChatTimelineStyle.Standard, SessionChatDisplayPreferences.timelineStylePreference)
        assertFalse(SessionChatDisplayPreferences.stepRailTimeline)
        assertFalse(SessionChatDisplayPreferences.activityCardTimeline)
    }
}
