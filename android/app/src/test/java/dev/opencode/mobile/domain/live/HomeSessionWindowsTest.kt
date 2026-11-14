package dev.opencode.mobile.domain.live

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSessionWindowsTest {
    @Test
    fun startsAtOnePage() {
        assertEquals(HomeSessionPaging.PAGE_SIZE, HomeSessionWindows().limit("/repo"))
    }

    @Test
    fun cacheSeedOfTwoPagesStartsTheFirstLoadAtTwoPages() {
        val windows = HomeSessionWindows()
        windows.seedFromCache("/repo", 100)
        assertEquals(100, windows.limit("/repo"))
        assertEquals(HomeSessionPaging.PAGE_SIZE, windows.limit("/other"))
    }

    @Test
    fun cacheSeedRoundsUpToAWholePage() {
        val windows = HomeSessionWindows()
        windows.seedFromCache("/a", 51)
        windows.seedFromCache("/b", 12)
        assertEquals(100, windows.limit("/a"))
        assertEquals(50, windows.limit("/b"))
    }

    @Test
    fun cacheSeedIsCappedAtFourPages() {
        val windows = HomeSessionWindows()
        windows.seedFromCache("/repo", 1_000)
        assertEquals(200, windows.limit("/repo"))
    }

    @Test
    fun emptyCacheSeedLeavesTheWindowUnchanged() {
        val windows = HomeSessionWindows()
        windows.seedFromCache("/repo", 0)
        assertEquals(HomeSessionPaging.PAGE_SIZE, windows.limit("/repo"))
    }

    @Test
    fun cacheSeedAfterACommittedLoadLeavesTheWindowUnchanged() {
        val windows = HomeSessionWindows()
        windows.markCommitted("/repo")
        windows.seedFromCache("/repo", 150)
        assertEquals(HomeSessionPaging.PAGE_SIZE, windows.limit("/repo"))
    }

    @Test
    fun cacheSeedNeverShrinksAGrownWindow() {
        val windows = HomeSessionWindows()
        repeat(3) { windows.grow("/repo") }
        windows.seedFromCache("/repo", 60)
        assertEquals(200, windows.limit("/repo"))
    }

    @Test
    fun clearForgetsWindowsAndCommits() {
        val windows = HomeSessionWindows()
        windows.grow("/repo")
        windows.markCommitted("/repo")
        windows.clear()
        assertEquals(HomeSessionPaging.PAGE_SIZE, windows.limit("/repo"))
        windows.seedFromCache("/repo", 100)
        assertEquals(100, windows.limit("/repo"))
    }
}
