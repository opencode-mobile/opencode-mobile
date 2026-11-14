package dev.opencode.mobile.ui.whatsnew

import dev.opencode.mobile.domain.releasenotes.ReleaseNote
import dev.opencode.mobile.domain.releasenotes.ReleaseNoteItem
import dev.opencode.mobile.domain.releasenotes.ReleaseNoteKind
import dev.opencode.mobile.domain.releasenotes.SemanticVersion
import dev.opencode.mobile.domain.releasenotes.WhatsNew
import dev.opencode.mobile.domain.releasenotes.WhatsNewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = InMemoryStore(lastSeen = "1.3.0")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun sheet_waitsForAnIdleHomeAndRecordsWhenItAppears() =
        runTest(dispatcher) {
            val viewModel = WhatsNewSheetViewModel(whatsNew(), dispatcher)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.visible)
            assertEquals("1.3.0", store.lastSeenReleaseNotesVersion)

            viewModel.onHomeIdleChange(true)

            val state = viewModel.state.value
            assertTrue(state.visible)
            assertEquals(listOf("1.5.0", "1.4.0"), state.releases.map { it.version.toString() })
            assertEquals(1, state.skippedCount)
            assertEquals("1.5.0", store.lastSeenReleaseNotesVersion)
            assertTrue(store.badgePending)
        }

    @Test
    fun sheet_presentsWhenHomeWasAlreadyIdleBeforeTheDecisionLoaded() =
        runTest(dispatcher) {
            val viewModel = WhatsNewSheetViewModel(whatsNew(), dispatcher)
            viewModel.onHomeIdleChange(true)
            assertFalse(viewModel.state.value.visible)

            advanceUntilIdle()

            assertTrue(viewModel.state.value.visible)
        }

    @Test
    fun sheet_showsOncePerProcessAndKeepsItsContentWhileDismissing() =
        runTest(dispatcher) {
            val whatsNew = whatsNew()
            val viewModel = WhatsNewSheetViewModel(whatsNew, dispatcher)
            advanceUntilIdle()
            viewModel.onHomeIdleChange(true)

            viewModel.dismiss()
            viewModel.onHomeIdleChange(false)
            viewModel.onHomeIdleChange(true)

            assertFalse(viewModel.state.value.visible)
            assertEquals(2, viewModel.state.value.releases.size)

            val recreated = WhatsNewSheetViewModel(whatsNew, dispatcher)
            advanceUntilIdle()
            recreated.onHomeIdleChange(true)
            assertFalse(recreated.state.value.visible)
        }

    @Test
    fun sheet_neverShowsOnAFreshInstall() =
        runTest(dispatcher) {
            val freshStore = InMemoryStore(lastSeen = null)
            val viewModel =
                WhatsNewSheetViewModel(
                    WhatsNew({ releases }, freshStore, installedVersionName = "1.5.0", isUpgrade = { false }),
                    dispatcher,
                )
            advanceUntilIdle()
            viewModel.onHomeIdleChange(true)

            assertFalse(viewModel.state.value.visible)
            assertEquals("1.5.0", freshStore.lastSeenReleaseNotesVersion)
            assertFalse(freshStore.badgePending)
        }

    @Test
    fun history_listsReleasesUpToInstalledAndClearsTheBadge() =
        runTest(dispatcher) {
            store.badgePending = true
            val whatsNew = whatsNew(installed = "1.4.0")

            val viewModel = WhatsNewHistoryViewModel(whatsNew, dispatcher)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.loaded)
            assertEquals("1.4.0", state.installedVersion)
            assertEquals(listOf("1.4.0", "1.3.0"), state.releases.map { it.version.toString() })
            assertFalse(store.badgePending)
            assertFalse(whatsNew.badgePending.value)
        }

    private val releases = listOf("1.5.0", "1.4.0", "1.3.0").map(::release)

    private fun whatsNew(installed: String = "1.5.0-internal.2") =
        WhatsNew({ releases }, store, installedVersionName = installed, isUpgrade = { true })

    private class InMemoryStore(
        lastSeen: String?,
    ) : WhatsNewStore {
        override var lastSeenReleaseNotesVersion: String? = lastSeen
        override var badgePending: Boolean = false
    }

    private fun release(version: String) =
        ReleaseNote(
            version = checkNotNull(SemanticVersion.parse(version)),
            released = null,
            items = listOf(ReleaseNoteItem(ReleaseNoteKind.New, "edit", "In $version", "")),
        )
}
