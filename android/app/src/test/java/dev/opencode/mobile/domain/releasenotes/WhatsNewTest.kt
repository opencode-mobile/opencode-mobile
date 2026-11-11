package dev.opencode.mobile.domain.releasenotes

import dev.opencode.mobile.domain.preferences.InMemoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    private val releases = listOf("1.6.0", "1.5.0", "1.3.1", "1.3.0", "1.2.0").map(::release)

    @Test
    fun decide_freshInstallShowsNothingAndRecordsInstalled() {
        val decision = WhatsNewLaunchPolicy.decide("1.5.0", lastSeen = null, isUpgrade = { false }, releases)

        assertTrue(decision.releases.isEmpty())
        assertEquals(SemanticVersion(1, 5, 0), decision.recordLastSeen)
    }

    @Test
    fun decide_firstVersionWithWhatsNewShowsOnlyTheInstalledRelease() {
        val decision = WhatsNewLaunchPolicy.decide("1.5.0", lastSeen = null, isUpgrade = { true }, releases)

        assertEquals(listOf("1.5.0"), decision.releases.versions())
        assertEquals(SemanticVersion(1, 5, 0), decision.recordLastSeen)
    }

    @Test
    fun decide_firstVersionWithoutNotesForItShowsNothingButRecords() {
        val decision = WhatsNewLaunchPolicy.decide("1.4.0", lastSeen = null, isUpgrade = { true }, releases)

        assertTrue(decision.releases.isEmpty())
        assertEquals(SemanticVersion(1, 4, 0), decision.recordLastSeen)
    }

    @Test
    fun decide_updateShowsEveryReleaseAfterLastSeenUpToInstalledNewestFirst() {
        val decision =
            WhatsNewLaunchPolicy.decide("1.5.0-internal.3", lastSeen = "1.3.0", isUpgrade = { error("not asked") }, releases)

        assertEquals(listOf("1.5.0", "1.3.1"), decision.releases.versions())
        assertEquals(SemanticVersion(1, 5, 0), decision.recordLastSeen)
    }

    @Test
    fun decide_updateWithNoNotesInRangeShowsNothingButRecords() {
        val decision = WhatsNewLaunchPolicy.decide("1.4.2", lastSeen = "1.3.1", isUpgrade = { true }, releases)

        assertTrue(decision.releases.isEmpty())
        assertEquals(SemanticVersion(1, 4, 2), decision.recordLastSeen)
    }

    @Test
    fun decide_sameVersionShowsNothingAndRecordsNothing() {
        val decision = WhatsNewLaunchPolicy.decide("1.5.0", lastSeen = "1.5.0", isUpgrade = { true }, releases)

        assertTrue(decision.releases.isEmpty())
        assertNull(decision.recordLastSeen)
    }

    @Test
    fun decide_downgradeShowsNothingAndKeepsTheHigherLastSeen() {
        val decision = WhatsNewLaunchPolicy.decide("1.3.0", lastSeen = "1.5.0", isUpgrade = { true }, releases)

        assertTrue(decision.releases.isEmpty())
        assertNull(decision.recordLastSeen)
    }

    @Test
    fun pendingReleases_recordsRightAwayWhenThereIsNothingToShow() {
        val store = InMemoryStore()

        val shown = whatsNew(store, installed = "1.5.0", isUpgrade = false).pendingReleases()

        assertTrue(shown.isEmpty())
        assertEquals("1.5.0", store.lastSeenReleaseNotesVersion)
        assertFalse(store.badgePending)
    }

    @Test
    fun pendingReleases_recordsOnlyOncePresented() {
        val store = InMemoryStore(lastSeen = "1.3.1")
        val whatsNew = whatsNew(store, installed = "1.5.0")

        assertEquals(listOf("1.5.0"), whatsNew.pendingReleases().versions())
        assertEquals("1.3.1", store.lastSeenReleaseNotesVersion)
        assertFalse(whatsNew.badgePending.value)

        whatsNew.markPresented()

        assertEquals("1.5.0", store.lastSeenReleaseNotesVersion)
        assertTrue(store.badgePending)
        assertTrue(whatsNew.badgePending.value)
        assertTrue(whatsNew.pendingReleases().isEmpty())
    }

    @Test
    fun pendingReleases_staysPendingInANewProcessWhenNeverPresented() {
        val store = InMemoryStore(lastSeen = "1.3.1")
        whatsNew(store, installed = "1.5.0").pendingReleases()

        assertEquals(listOf("1.5.0"), whatsNew(store, installed = "1.5.0").pendingReleases().versions())
    }

    @Test
    fun pendingReleases_isEmptyInTheNextProcessOncePresented() {
        val store = InMemoryStore(lastSeen = "1.3.1")
        whatsNew(store, installed = "1.5.0").apply {
            pendingReleases()
            markPresented()
        }

        assertTrue(whatsNew(store, installed = "1.5.0").pendingReleases().isEmpty())
    }

    @Test
    fun history_listsReleasesUpToInstalledAndClearBadgeClearsIt() {
        val store = InMemoryStore(lastSeen = "1.5.0", badgePending = true)
        val whatsNew = whatsNew(store, installed = "1.5.0")

        assertEquals(listOf("1.5.0", "1.3.1", "1.3.0", "1.2.0"), whatsNew.history().versions())
        assertTrue(whatsNew.badgePending.value)

        whatsNew.clearBadge()

        assertFalse(whatsNew.badgePending.value)
        assertFalse(store.badgePending)
    }

    @Test
    fun sharedPreferencesStore_roundTripsBothValues() {
        val prefs = InMemoryPreferences()
        val store = SharedPreferencesWhatsNewStore(prefs)

        store.lastSeenReleaseNotesVersion = "1.5.0"
        store.badgePending = true

        val reread = SharedPreferencesWhatsNewStore(prefs)
        assertEquals("1.5.0", reread.lastSeenReleaseNotesVersion)
        assertTrue(reread.badgePending)
        reread.lastSeenReleaseNotesVersion = null
        assertNull(SharedPreferencesWhatsNewStore(prefs).lastSeenReleaseNotesVersion)
    }

    private fun whatsNew(
        store: WhatsNewStore,
        installed: String,
        isUpgrade: Boolean = true,
    ) = WhatsNew(
        loadReleaseNotes = { releases },
        store = store,
        installedVersionName = installed,
        isUpgrade = { isUpgrade },
    )

    private class InMemoryStore(
        lastSeen: String? = null,
        badgePending: Boolean = false,
    ) : WhatsNewStore {
        override var lastSeenReleaseNotesVersion: String? = lastSeen
        override var badgePending: Boolean = badgePending
    }

    private fun release(version: String) =
        ReleaseNote(
            version = checkNotNull(SemanticVersion.parse(version)),
            released = null,
            items = listOf(ReleaseNoteItem(ReleaseNoteKind.New, "edit", "In $version", "")),
        )

    private fun List<ReleaseNote>.versions(): List<String> = map { it.version.toString() }
}
