package dev.opencode.hexcode.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperSettingsTest {
    private class FakeStore : DeveloperSettingsStore {
        val saved = MutableStateFlow(false)
        override val developerSettings = saved

        override suspend fun setDeveloperSettings(on: Boolean) {
            saved.value = on
        }
    }

    /** Taps [count] times, [gapMs] apart from [startMs]; returns which taps unlocked. */
    private suspend fun DeveloperSettings.tap(
        count: Int,
        startMs: Long = 10_000,
        gapMs: Long = 300,
    ): List<Boolean> = (0 until count).map { tapVersion(startMs + it * gapMs) }

    @Test
    fun tenQuickTapsUnlock() =
        runTest {
            val store = FakeStore()
            val developer = DeveloperSettings(store)
            val results = developer.tap(10)
            assertEquals(List(9) { false } + true, results)
            assertTrue(store.saved.value)
            assertTrue(developer.unlocked.first())
        }

    @Test
    fun nineTapsDoNothing() =
        runTest {
            val store = FakeStore()
            DeveloperSettings(store).tap(9)
            assertFalse(store.saved.value)
        }

    @Test
    fun tapsExactlyTwoSecondsApartStillCount() =
        runTest {
            val store = FakeStore()
            assertTrue(DeveloperSettings(store).tap(10, gapMs = DeveloperSettings.TAP_WINDOW_MS).last())
        }

    @Test
    fun aGapOverTwoSecondsStartsTheCountAgain() =
        runTest {
            val store = FakeStore()
            val developer = DeveloperSettings(store)
            developer.tap(9, startMs = 10_000)
            // The tenth tap comes 2.001 s after the ninth: it is the first of a new run.
            val late = 10_000 + 8 * 300 + DeveloperSettings.TAP_WINDOW_MS + 1
            assertFalse(developer.tapVersion(late))
            assertFalse(store.saved.value)
            val again = developer.tap(9, startMs = late + 300)
            assertEquals(List(8) { false } + true, again)
            assertTrue(store.saved.value)
        }

    @Test
    fun theUnlockIsSavedAndReadBack() =
        runTest {
            val store = FakeStore()
            DeveloperSettings(store).tap(10)
            // A new launch reads the saved unlock.
            val relaunched = DeveloperSettings(store)
            assertTrue(relaunched.unlocked.first())
        }

    @Test
    fun tappingWhileUnlockedDoesNotUnlockAgain() =
        runTest {
            val store = FakeStore()
            val developer = DeveloperSettings(store)
            developer.tap(10)
            assertEquals(List(10) { false }, developer.tap(10, startMs = 20_000))
            assertTrue(store.saved.value)
        }

    @Test
    fun hideLocksAgainAndTheNextUnlockNeedsAllTenTaps() =
        runTest {
            val store = FakeStore()
            val developer = DeveloperSettings(store)
            developer.tap(10)
            developer.tap(5, startMs = 20_000)
            developer.hide()
            assertFalse(store.saved.value)
            assertFalse(developer.unlocked.first())
            assertEquals(List(9) { false } + true, developer.tap(10, startMs = 21_600))
        }
}
