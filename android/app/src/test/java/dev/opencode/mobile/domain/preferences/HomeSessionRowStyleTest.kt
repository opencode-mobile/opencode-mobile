package dev.opencode.mobile.domain.preferences

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSessionRowStyleTest {
    private val store = InMemoryPreferences()

    @After
    fun tearDown() {
        HomeDisplayPreferences.initialize(InMemoryPreferences())
        HomeDisplayPreferences.clear()
    }

    @Test
    fun fromWire_readsStoredValues() {
        assertEquals(HomeSessionRowStyle.Compact, HomeSessionRowStyle.fromWire("compact"))
        assertEquals(HomeSessionRowStyle.Classic, HomeSessionRowStyle.fromWire("classic"))
        assertNull(HomeSessionRowStyle.fromWire("tiles"))
        assertNull(HomeSessionRowStyle.fromWire(null))
    }

    @Test
    fun nothingStored_isCompact() {
        HomeDisplayPreferences.initialize(store)

        assertEquals(HomeSessionRowStyle.Compact, HomeDisplayPreferences.sessionRowStyle)
    }

    @Test
    fun unknownStoredValue_isCompact() {
        store.values["session_row_style"] = "tiles"
        HomeDisplayPreferences.initialize(store)

        assertEquals(HomeSessionRowStyle.Compact, HomeDisplayPreferences.sessionRowStyle)
    }

    @Test
    fun choice_survivesRelaunch() {
        HomeDisplayPreferences.initialize(store)
        HomeDisplayPreferences.updateSessionRowStyle(HomeSessionRowStyle.Classic)

        assertEquals("classic", store.values["session_row_style"])
        HomeDisplayPreferences.initialize(InMemoryPreferences())
        HomeDisplayPreferences.initialize(store)
        assertEquals(HomeSessionRowStyle.Classic, HomeDisplayPreferences.sessionRowStyle)
    }

    @Test
    fun clear_resetsToCompact() {
        HomeDisplayPreferences.initialize(store)
        HomeDisplayPreferences.updateSessionRowStyle(HomeSessionRowStyle.Classic)

        HomeDisplayPreferences.clear()

        assertEquals(HomeSessionRowStyle.Compact, HomeDisplayPreferences.sessionRowStyle)
        assertFalse(store.values.containsKey("session_row_style"))
        HomeDisplayPreferences.initialize(store)
        assertEquals(HomeSessionRowStyle.Compact, HomeDisplayPreferences.sessionRowStyle)
    }
}
