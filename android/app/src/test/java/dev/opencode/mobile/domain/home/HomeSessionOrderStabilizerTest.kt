package dev.opencode.mobile.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSessionOrderStabilizerTest {
    private data class Item(
        val id: String,
        val title: String = id,
        val attention: Boolean = true,
        val ready: Boolean = false,
    )

    private val t0 = 1_000_000L

    @Test
    fun firstDesiredOrderAppliesImmediately() {
        val desired = recent("b", "a")

        val result = stabilize(previous = emptyList(), lastAppliedAt = null, desired = desired, now = t0)

        assertEquals(listOf("b", "a"), result.ids())
        assertEquals(t0, result.lastAppliedAtEpochMs)
        assertNull(result.nextApplyAtEpochMs)
    }

    @Test
    fun recencySwapWithinWindowKeepsOrderAndUpdatesText() {
        val applied = stabilize(previous = emptyList(), lastAppliedAt = null, desired = recent("a", "b"), now = t0)
        val swapped =
            recent(
                Item(id = "b", title = "Bee"),
                Item(id = "a", title = "Aye"),
            )

        val held = stabilize(previous = applied.sections, lastAppliedAt = t0, desired = swapped, now = t0 + 1_000)

        assertEquals(listOf("a", "b"), held.ids())
        assertEquals(listOf("Aye", "Bee"), held.titles())
        assertEquals(t0, held.lastAppliedAtEpochMs)
        assertEquals(t0 + HomeSessionOrderStabilizer.REORDER_INTERVAL_MS, held.nextApplyAtEpochMs)
    }

    @Test
    fun flushAppliesDesiredOrderAfterInterval() {
        val applied = stabilize(previous = emptyList(), lastAppliedAt = null, desired = recent("a", "b"), now = t0)
        val swapped = recent("b", "a")
        val held = stabilize(previous = applied.sections, lastAppliedAt = t0, desired = swapped, now = t0 + 1_000)

        val flushed =
            stabilize(
                previous = held.sections,
                lastAppliedAt = held.lastAppliedAtEpochMs,
                desired = swapped,
                now = t0 + HomeSessionOrderStabilizer.REORDER_INTERVAL_MS,
            )

        assertEquals(listOf("b", "a"), flushed.ids())
        assertEquals(t0 + HomeSessionOrderStabilizer.REORDER_INTERVAL_MS, flushed.lastAppliedAtEpochMs)
        assertNull(flushed.nextApplyAtEpochMs)
    }

    @Test
    fun newSessionAppliesImmediatelyDuringLock() {
        val applied = stabilize(previous = emptyList(), lastAppliedAt = null, desired = recent("a", "b"), now = t0)
        val withNew = recent("c", "a", "b")

        val result = stabilize(previous = applied.sections, lastAppliedAt = t0, desired = withNew, now = t0 + 500)

        assertEquals(listOf("c", "a", "b"), result.ids())
        assertEquals(t0 + 500, result.lastAppliedAtEpochMs)
        assertNull(result.nextApplyAtEpochMs)
    }

    @Test
    fun attentionChangeAppliesImmediatelyDuringLock() {
        val applied =
            stabilize(
                previous = emptyList(),
                lastAppliedAt = null,
                desired =
                    listOf(
                        recentSection(Item(id = "run", attention = true)),
                        daySection(Item(id = "idle", attention = false)),
                    ),
                now = t0,
            )
        val idleNowRunning =
            listOf(
                recentSection(
                    Item(id = "idle", attention = true),
                    Item(id = "run", attention = true),
                ),
            )

        val result =
            stabilize(previous = applied.sections, lastAppliedAt = t0, desired = idleNowRunning, now = t0 + 200)

        assertEquals(listOf("idle", "run"), result.ids())
        assertEquals(t0 + 200, result.lastAppliedAtEpochMs)
    }

    @Test
    fun becomingReadyAppliesImmediatelyDuringLock() {
        val applied =
            stabilize(
                previous = emptyList(),
                lastAppliedAt = null,
                desired =
                    recent(
                        Item(id = "a", attention = true, ready = false),
                        Item(id = "b", attention = true, ready = false),
                    ),
                now = t0,
            )
        val bReady =
            recent(
                Item(id = "b", attention = true, ready = true),
                Item(id = "a", attention = true, ready = false),
            )

        val result = stabilize(previous = applied.sections, lastAppliedAt = t0, desired = bReady, now = t0 + 200)

        assertEquals(listOf("b", "a"), result.ids())
        assertEquals(t0 + 200, result.lastAppliedAtEpochMs)
    }

    @Test
    fun removedSessionDropsInPlaceWithoutReorderingHold() {
        val applied = stabilize(previous = emptyList(), lastAppliedAt = null, desired = recent("a", "b", "c"), now = t0)
        val withoutB = recent("c", "a")

        val held = stabilize(previous = applied.sections, lastAppliedAt = t0, desired = withoutB, now = t0 + 1_000)

        assertEquals(listOf("a", "c"), held.ids())
        assertEquals(t0, held.lastAppliedAtEpochMs)
        assertEquals(t0 + HomeSessionOrderStabilizer.REORDER_INTERVAL_MS, held.nextApplyAtEpochMs)
    }

    private fun stabilize(
        previous: List<HomeSessionLadderSection<Item>>,
        lastAppliedAt: Long?,
        desired: List<HomeSessionLadderSection<Item>>,
        now: Long,
    ) = HomeSessionOrderStabilizer.stabilize(
        previousSections = previous,
        previousLastAppliedAtEpochMs = lastAppliedAt,
        desired = desired,
        itemId = { it.id },
        isAttention = { it.attention },
        isReady = { it.ready },
        nowEpochMs = now,
    )

    private fun recent(vararg ids: String): List<HomeSessionLadderSection<Item>> = recent(*ids.map { Item(id = it) }.toTypedArray())

    private fun recent(vararg items: Item): List<HomeSessionLadderSection<Item>> = listOf(recentSection(*items))

    private fun recentSection(vararg items: Item) =
        HomeSessionLadderSection(
            id = "recent",
            kind = HomeSessionLadderKind.Recent,
            title = "Recent",
            items = items.toList(),
        )

    private fun daySection(vararg items: Item) =
        HomeSessionLadderSection(
            id = "day-today",
            kind = HomeSessionLadderKind.Day,
            title = "Today",
            items = items.toList(),
        )

    private fun HomeSessionOrderResult<Item>.ids(): List<String> = sections.flatMap { section -> section.items.map { it.id } }

    private fun HomeSessionOrderResult<Item>.titles(): List<String> = sections.flatMap { section -> section.items.map { it.title } }
}
