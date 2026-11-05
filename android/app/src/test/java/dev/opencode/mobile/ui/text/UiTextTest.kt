package dev.opencode.mobile.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    /** English-like fake: `%1$s`-style formats and a one/other plural, keyed by fake ids. */
    private class FakeLookup : UiTextLookup {
        val stringCalls = mutableListOf<Int>()

        override fun string(
            id: Int,
            args: Array<out Any>,
        ): String {
            stringCalls += id
            val format = strings.getValue(id)
            return if (args.isEmpty()) format else String.format(format, *args)
        }

        override fun plural(
            id: Int,
            count: Int,
            args: Array<out Any>,
        ): String {
            val (one, other) = plurals.getValue(id)
            return String.format(if (count == 1) one else other, *args)
        }
    }

    @Test
    fun resource_withoutArgs_resolvesTheString() {
        assertEquals("Servers", UiText.resource(TITLE).resolve(FakeLookup()))
    }

    @Test
    fun resource_fillsPositionalArgs() {
        val text = UiText.resource(REMOVE_NAMED, "Work laptop")

        assertEquals("Remove “Work laptop”?", text.resolve(FakeLookup()))
    }

    @Test
    fun resource_resolvesNestedUiTextArgs() {
        val text = UiText.resource(REMOVE_NAMED, UiText.resource(TITLE))

        assertEquals("Remove “Servers”?", text.resolve(FakeLookup()))
    }

    @Test
    fun plural_picksQuantityAndUsesCountAsTheArgument() {
        val lookup = FakeLookup()

        assertEquals("1 Connection Profile", UiText.plural(COUNT, 1).resolve(lookup))
        assertEquals("3 Connection Profiles", UiText.plural(COUNT, 3).resolve(lookup))
        assertEquals("0 Connection Profiles", UiText.plural(COUNT, 0).resolve(lookup))
    }

    @Test
    fun plural_withExplicitArgs_keepsCountSeparateFromArgs() {
        val text = UiText.plural(COUNT, 1, 42)

        assertEquals("42 Connection Profile", text.resolve(FakeLookup()))
    }

    @Test
    fun verbatim_isNeverLookedUp() {
        val lookup = FakeLookup()
        // A session title that happens to look like a format or a key stays as is.
        val text = UiText.verbatim("servers_title %1\$s")

        assertEquals("servers_title %1\$s", text.resolve(lookup))
        assertTrue(lookup.stringCalls.isEmpty())
    }

    @Test
    fun verbatimArg_isInsertedWithoutFormattingItsContent() {
        val text = UiText.resource(REMOVE_NAMED, UiText.verbatim("50% done"))

        assertEquals("Remove “50% done”?", text.resolve(FakeLookup()))
    }

    @Test
    fun equality_comparesIdsAndArgs() {
        assertEquals(UiText.resource(REMOVE_NAMED, "a"), UiText.resource(REMOVE_NAMED, "a"))
        assertNotEquals(UiText.resource(REMOVE_NAMED, "a"), UiText.resource(REMOVE_NAMED, "b"))
        assertEquals(UiText.plural(COUNT, 2), UiText.Plural(COUNT, 2, listOf(2)))
        assertNotEquals(UiText.verbatim("Servers"), UiText.resource(TITLE))
    }

    private companion object {
        const val TITLE = 1
        const val REMOVE_NAMED = 2
        const val COUNT = 3

        val strings =
            mapOf(
                TITLE to "Servers",
                REMOVE_NAMED to "Remove “%1\$s”?",
            )
        val plurals =
            mapOf(
                COUNT to ("%1\$d Connection Profile" to "%1\$d Connection Profiles"),
            )
    }
}
