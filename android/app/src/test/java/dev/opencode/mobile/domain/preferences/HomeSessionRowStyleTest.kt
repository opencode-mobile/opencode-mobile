package dev.opencode.mobile.domain.preferences

import android.content.SharedPreferences
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

/** SharedPreferences backed by a map; edits apply on [SharedPreferences.Editor.apply]. */
private class InMemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(
            key: String,
            value: String?,
        ) = apply { changes[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ) = apply { changes[key] = values }

        override fun putInt(
            key: String,
            value: Int,
        ) = apply { changes[key] = value }

        override fun putLong(
            key: String,
            value: Long,
        ) = apply { changes[key] = value }

        override fun putFloat(
            key: String,
            value: Float,
        ) = apply { changes[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = apply { changes[key] = value }

        override fun remove(key: String) = apply { removals += key }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(changes)
        }
    }
}
