package dev.opencode.mobile.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConcurrentMapSnapshotTest {
    @Test
    fun copyByEntryIteration_copiesEntries() {
        val source = mapOf("ses" to "busy")

        assertEquals(mapOf("ses" to "busy"), source.copyByEntryIteration())
    }

    @Test
    fun copyByEntryIteration_doesNotUseTheOneEntryFastPath() {
        val vanishing = VanishingSingletonMap()

        assertThrows(NoSuchElementException::class.java) { vanishing.toMap() }
        assertEquals(emptyMap<String, String>(), vanishing.copyByEntryIteration())
    }

    private class VanishingSingletonMap : AbstractMap<String, String>() {
        override val size: Int = 1

        override fun isEmpty(): Boolean = false

        override val entries: Set<Map.Entry<String, String>> =
            object : AbstractSet<Map.Entry<String, String>>() {
                override val size: Int = 1

                override fun iterator(): MutableIterator<Map.Entry<String, String>> =
                    object : MutableIterator<Map.Entry<String, String>> {
                        override fun hasNext(): Boolean = false

                        override fun next(): Map.Entry<String, String> = throw NoSuchElementException()

                        override fun remove() = error("unused")
                    }
            }
    }
}
