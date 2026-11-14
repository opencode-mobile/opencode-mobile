package dev.opencode.mobile.domain.app

import dev.opencode.mobile.domain.preferences.InMemoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NewFeaturesTest {
    private val key = "feature_a"

    private fun sync(
        state: NewFeaturesState,
        version: String,
        freshInstall: Boolean = false,
        keys: List<String> = listOf(key),
    ): NewFeaturesState = NewFeaturePolicy.sync(state, keys, version, isFreshInstall = { freshInstall })

    @Test
    fun freshInstall_isNotNew() {
        val state = sync(NewFeaturesState(), "1.5.0", freshInstall = true)

        assertTrue(state.initialized)
        assertEquals(NewFeaturePolicy.BASELINE, state.records.getValue(key).firstSeenVersion)
        assertFalse(NewFeaturePolicy.isNew(state, key, "1.5.0"))
    }

    @Test
    fun freshInstall_staysNotNewAfterUpdating() {
        val installed = sync(NewFeaturesState(), "1.5.0", freshInstall = true)
        val updated = sync(installed, "1.6.0")

        assertFalse(NewFeaturePolicy.isNew(updated, key, "1.6.0"))
    }

    @Test
    fun existingInstallUpdating_isNew() {
        val state = sync(NewFeaturesState(), "1.5.0", freshInstall = false)

        assertTrue(NewFeaturePolicy.isNew(state, key, "1.5.0"))
    }

    @Test
    fun sameVersionRelaunch_isStillNew() {
        val first = sync(NewFeaturesState(), "1.5.0")
        val relaunched = sync(first, "1.5.0")

        assertEquals(first, relaunched)
        assertTrue(NewFeaturePolicy.isNew(relaunched, key, "1.5.0"))
    }

    @Test
    fun versionChange_isNotNew() {
        val first = sync(NewFeaturesState(), "1.5.0")
        val updated = sync(first, "1.5.1")

        assertFalse(NewFeaturePolicy.isNew(updated, key, "1.5.1"))
    }

    @Test
    fun skippedRelease_isNewInTheLandingVersion() {
        // 1.4.0 had the store but not the feature; the user skips 1.5.0 and lands on 1.6.0.
        val before = sync(NewFeaturesState(), "1.4.0", keys = emptyList())
        val landed = sync(before, "1.6.0")

        assertTrue(NewFeaturePolicy.isNew(landed, key, "1.6.0"))
        assertFalse(NewFeaturePolicy.isNew(sync(landed, "1.7.0"), key, "1.7.0"))
    }

    @Test
    fun used_isNotNew() {
        val state = NewFeaturePolicy.markUsed(sync(NewFeaturesState(), "1.5.0"), key)

        assertFalse(NewFeaturePolicy.isNew(state, key, "1.5.0"))
        assertFalse(NewFeaturePolicy.isNew(sync(state, "1.5.0"), key, "1.5.0"))
    }

    @Test
    fun keyAddedAfterInitialization_isNewEvenOnAnInstallThatStartedFresh() {
        val installed = sync(NewFeaturesState(), "1.5.0", freshInstall = true, keys = emptyList())
        // The install was never updated according to the package timestamps; it no longer matters.
        val later = sync(installed, "1.6.0", freshInstall = true)

        assertTrue(NewFeaturePolicy.isNew(later, key, "1.6.0"))
    }

    @Test
    fun sync_asksAboutFreshInstallOnlyBeforeInitialization() {
        var asked = 0
        val isFreshInstall = {
            asked += 1
            false
        }
        val first = NewFeaturePolicy.sync(NewFeaturesState(), listOf(key), "1.5.0", isFreshInstall)
        NewFeaturePolicy.sync(first, listOf(key, "feature_b"), "1.6.0", isFreshInstall)

        assertEquals(1, asked)
    }

    @Test
    fun unknownKey_isNotNew() {
        assertFalse(NewFeaturePolicy.isNew(sync(NewFeaturesState(), "1.5.0"), "missing", "1.5.0"))
    }

    @Test
    fun store_persistsUnderNamespacedKeysAndHidesTheBadgeOnceUsed() {
        val prefs = InMemoryPreferences()
        val feature = NewFeature.SelectMessageText

        NewFeatures.initialize(prefs, currentVersion = "1.5.0", isFreshInstall = { false })
        assertTrue(NewFeatures.isNew(feature))
        assertEquals(true, prefs.values["new_features.initialized"])
        assertEquals("1.5.0", prefs.values["new_features.select_message_text.first_seen_version"])

        NewFeatures.markUsed(feature)
        assertFalse(NewFeatures.isNew(feature))
        assertEquals(true, prefs.values["new_features.select_message_text.used"])

        // A relaunch reads the same answer back.
        NewFeatures.initialize(prefs, currentVersion = "1.5.0", isFreshInstall = { false })
        assertFalse(NewFeatures.isNew(feature))
    }

    @Test
    fun store_relaunchKeepsTheBadgeAndAnUpdateDropsIt() {
        val prefs = InMemoryPreferences()
        val feature = NewFeature.SelectMessageText

        NewFeatures.initialize(prefs, currentVersion = "1.5.0", isFreshInstall = { false })
        NewFeatures.initialize(prefs, currentVersion = "1.5.0", isFreshInstall = { false })
        assertTrue(NewFeatures.isNew(feature))

        NewFeatures.initialize(prefs, currentVersion = "1.5.1", isFreshInstall = { false })
        assertFalse(NewFeatures.isNew(feature))
    }

    @Test
    fun store_freshInstallRecordsBaseline() {
        val prefs = InMemoryPreferences()

        NewFeatures.initialize(prefs, currentVersion = "1.5.0", isFreshInstall = { true })

        assertFalse(NewFeatures.isNew(NewFeature.SelectMessageText))
        assertEquals(
            NewFeaturePolicy.BASELINE,
            prefs.values["new_features.select_message_text.first_seen_version"],
        )
    }

    @Test
    fun registry_keysAreUnique() {
        val keys = NewFeature.entries.map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals("select_message_text", NewFeature.SelectMessageText.key)
        assertEquals(LocalDate.of(2027, 2, 10), NewFeature.SelectMessageText.removeAfter)
    }

    @Test
    fun registry_hasNoExpiredEntries() {
        val today = LocalDate.now()
        val expired = NewFeature.entries.filter { today.isAfter(it.removeAfter) }

        assertTrue(
            "New feature badges past their removeAfter date: " +
                expired.joinToString { "${it.name} (${it.removeAfter})" } +
                ". Remove the registry entry and its badge call sites.",
            expired.isEmpty(),
        )
    }
}
