package dev.opencode.mobile.domain.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * Features that carry a "New" badge. A badge shows from the app version that first brought the
 * feature to this install until the next app update, or until the feature is used.
 *
 * To badge a feature, add an entry here and call [NewFeatures.isNew] and [NewFeatures.markUsed]
 * where it is offered. [removeAfter] is the date by which the entry and those calls must be
 * deleted; a unit test fails once it has passed.
 */
enum class NewFeature(
    /** Stable storage key. Never rename or reuse one. */
    val key: String,
    val removeAfter: LocalDate,
) {
    SelectMessageText(key = "select_message_text", removeAfter = LocalDate.of(2027, 2, 10)),
}

/** What the store remembers about one [NewFeature]. */
internal data class NewFeatureRecord(
    /** The versionName that first showed the feature, [NewFeaturePolicy.BASELINE], or null when unseen. */
    val firstSeenVersion: String? = null,
    val used: Boolean = false,
)

internal data class NewFeaturesState(
    /** True once the first [NewFeaturePolicy.sync] ran on this install. */
    val initialized: Boolean = false,
    val records: Map<String, NewFeatureRecord> = emptyMap(),
)

/** Pure rules for the "New" badge. No I/O. */
internal object NewFeaturePolicy {
    /** Recorded for features that shipped with a fresh install; never equal to a versionName. */
    const val BASELINE = "baseline"

    /**
     * Records where each of [keys] was first seen. A fresh install starts with every feature at
     * [BASELINE], so nothing is new to someone who never had the app without it. Otherwise a
     * feature is first seen in [currentVersion]. [isFreshInstall] is asked only before the
     * store is initialized.
     */
    fun sync(
        state: NewFeaturesState,
        keys: Collection<String>,
        currentVersion: String,
        isFreshInstall: () -> Boolean,
    ): NewFeaturesState {
        val firstSeen = if (!state.initialized && isFreshInstall()) BASELINE else currentVersion
        val records = state.records.toMutableMap()
        keys.forEach { key ->
            val record = records[key] ?: NewFeatureRecord()
            if (record.firstSeenVersion == null) records[key] = record.copy(firstSeenVersion = firstSeen)
        }
        return NewFeaturesState(initialized = true, records = records)
    }

    fun isNew(
        state: NewFeaturesState,
        key: String,
        currentVersion: String,
    ): Boolean {
        val record = state.records[key] ?: return false
        return !record.used && record.firstSeenVersion == currentVersion
    }

    fun markUsed(
        state: NewFeaturesState,
        key: String,
    ): NewFeaturesState {
        val record = state.records[key] ?: NewFeatureRecord()
        if (record.used) return state
        return state.copy(records = state.records + (key to record.copy(used = true)))
    }
}

/** SharedPreferences names and encoding for [NewFeatures]. */
internal object NewFeaturesCodec {
    const val KEY_INITIALIZED = "new_features.initialized"

    fun firstSeenVersionKey(key: String): String = "new_features.$key.first_seen_version"

    fun usedKey(key: String): String = "new_features.$key.used"

    fun read(
        prefs: SharedPreferences,
        keys: Collection<String>,
    ): NewFeaturesState =
        NewFeaturesState(
            initialized = prefs.getBoolean(KEY_INITIALIZED, false),
            records =
                keys
                    .associateWith { key ->
                        NewFeatureRecord(
                            firstSeenVersion = prefs.getString(firstSeenVersionKey(key), null),
                            used = prefs.getBoolean(usedKey(key), false),
                        )
                    }.filterValues { it != NewFeatureRecord() },
        )

    fun write(
        prefs: SharedPreferences,
        state: NewFeaturesState,
    ) {
        val editor = prefs.edit().putBoolean(KEY_INITIALIZED, state.initialized)
        state.records.forEach { (key, record) ->
            if (record.firstSeenVersion == null) {
                editor.remove(firstSeenVersionKey(key))
            } else {
                editor.putString(firstSeenVersionKey(key), record.firstSeenVersion)
            }
            editor.putBoolean(usedKey(key), record.used)
        }
        editor.apply()
    }
}

/**
 * The one place that decides whether a [NewFeature] shows its "New" badge. Reads are snapshot
 * state, so a badge leaves the screen as soon as [markUsed] runs.
 *
 * [initialize] runs once from `Application.onCreate`, before any composition.
 */
object NewFeatures {
    private const val PREFS_NAME = "opencode_new_features"

    private var state by mutableStateOf(NewFeaturesState())
    private var currentVersion: String = ""
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        initialize(
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            currentVersion = AppVersion.from(appContext).version,
            isFreshInstall = { appContext.isFreshInstall() },
        )
    }

    internal fun initialize(
        prefs: SharedPreferences,
        currentVersion: String,
        isFreshInstall: () -> Boolean,
    ) {
        val keys = NewFeature.entries.map { it.key }
        val stored = NewFeaturesCodec.read(prefs, keys)
        val synced = NewFeaturePolicy.sync(stored, keys, currentVersion, isFreshInstall)
        if (synced != stored) NewFeaturesCodec.write(prefs, synced)
        preferences = prefs
        this.currentVersion = currentVersion
        state = synced
    }

    /** True while [feature] should show its "New" badge. */
    fun isNew(feature: NewFeature): Boolean = NewFeaturePolicy.isNew(state, feature.key, currentVersion)

    /** Call when the user uses [feature]; its badge is gone for good. */
    fun markUsed(feature: NewFeature) {
        val updated = NewFeaturePolicy.markUsed(state, feature.key)
        if (updated == state) return
        state = updated
        preferences?.let { NewFeaturesCodec.write(it, updated) }
    }
}

/** An install that has never been updated: both package timestamps are the install time. */
private fun Context.isFreshInstall(): Boolean {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    return packageInfo.firstInstallTime == packageInfo.lastUpdateTime
}
