package dev.opencode.mobile.highlight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EmbeddedStatesTest {
    @Test
    fun aPairWithAnInnerStateOfZeroIsTheHostStateItself() {
        val states = EmbeddedStates()
        assertEquals(5, states.join(5, 0))
        assertEquals(5, states.host(5))
        assertEquals(0, states.inner(5))
        assertEquals(0, states.size)
    }

    @Test
    fun pairsAreInternedCanonicallyAndReadBack() {
        val states = EmbeddedStates()
        val a = states.join(3, 7)
        val b = states.join(3, -1)
        assertTrue(a < 0 && b < 0)
        assertNotEquals(a, b)
        assertEquals(a, states.join(3, 7))
        assertEquals(3, states.host(a))
        assertEquals(7, states.inner(a))
        // An inner state may itself be a pair from the same table.
        val nested = states.join(9, a)
        assertEquals(a, states.inner(nested))
        assertEquals(3, states.size)
    }

    @Test
    fun theTableGrowsKeepingEveryPairAndStopsAtItsCap() {
        val states = EmbeddedStates()
        val joined = IntArray(5_000) { states.join(it % 7, it + 1) }
        for (index in joined.indices) {
            assertEquals(joined[index], states.join(index % 7, index + 1))
            assertEquals(index + 1, states.inner(joined[index]))
        }
        repeat(EmbeddedStates.MAX_PAIRS) { states.join(1, it + 10_000) }
        assertEquals(EmbeddedStates.MAX_PAIRS, states.size)
        // Full: a new pair drops its inner state, existing pairs still resolve.
        assertEquals(4, states.join(4, -77))
        assertEquals(joined[42], states.join(42 % 7, 43))
        states.clear()
        assertEquals(0, states.size)
        assertEquals(0.inv(), states.join(1, 1))
    }
}
