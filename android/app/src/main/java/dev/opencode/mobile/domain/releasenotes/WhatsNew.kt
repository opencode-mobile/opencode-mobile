package dev.opencode.mobile.domain.releasenotes

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the What's new sheet does on a launch.
 *
 * [releases] are shown newest first; empty shows nothing. [recordLastSeen] is the version to
 * store as seen, or null to leave the stored one alone. With releases to show it is recorded
 * when the sheet appears, otherwise right away.
 */
data class WhatsNewLaunchDecision(
    val releases: List<ReleaseNote>,
    val recordLastSeen: SemanticVersion?,
)

/** Pure launch rules for the What's new sheet. No I/O. */
object WhatsNewLaunchPolicy {
    /**
     * - No stored version and no upgrade: a fresh install. Show nothing, record [installedVersion].
     * - No stored version but an upgrade: the first version with What's new. Show only the
     *   release equal to [installedVersion], if there is one.
     * - Installed newer than [lastSeen]: show every release after [lastSeen] up to the installed one.
     * - Same version or a downgrade: show nothing and keep [lastSeen].
     */
    fun decide(
        installedVersion: String,
        lastSeen: String?,
        isUpgrade: () -> Boolean,
        releases: List<ReleaseNote>,
    ): WhatsNewLaunchDecision {
        val installed = SemanticVersion.parse(installedVersion) ?: return WhatsNewLaunchDecision(emptyList(), null)
        val seen = SemanticVersion.parse(lastSeen)
        if (seen == null) {
            val shown = if (isUpgrade()) releases.filter { it.version == installed } else emptyList()
            return WhatsNewLaunchDecision(shown, installed)
        }
        if (installed <= seen) return WhatsNewLaunchDecision(emptyList(), null)
        val shown =
            releases
                .filter { it.version > seen && it.version <= installed }
                .sortedByDescending { it.version }
        return WhatsNewLaunchDecision(shown, installed)
    }
}

/** Where What's new keeps the last version whose notes were seen and the Settings badge. */
interface WhatsNewStore {
    var lastSeenReleaseNotesVersion: String?
    var badgePending: Boolean
}

class SharedPreferencesWhatsNewStore(
    private val prefs: SharedPreferences,
) : WhatsNewStore {
    override var lastSeenReleaseNotesVersion: String?
        get() = prefs.getString(KEY_LAST_SEEN, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_LAST_SEEN) else putString(KEY_LAST_SEEN, value) }.apply()
        }

    override var badgePending: Boolean
        get() = prefs.getBoolean(KEY_BADGE_PENDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BADGE_PENDING, value).apply()
        }

    companion object {
        const val PREFS_NAME = "opencode_whats_new"
        const val KEY_LAST_SEEN = "last_seen_release_notes_version"
        const val KEY_BADGE_PENDING = "whats_new_badge_pending"
    }
}

/**
 * What's new for this install: the release notes up to the installed version, the sheet to show
 * after an update (decided once per process), and the Settings badge.
 *
 * Blocking reads; call [pendingReleases] and [history] off the main thread.
 */
class WhatsNew(
    private val loadReleaseNotes: () -> List<ReleaseNote>,
    private val store: WhatsNewStore,
    /** The app's versionName, possibly with a prerelease suffix. */
    private val installedVersionName: String,
    /** Asked only when nothing is stored yet: true when this install was updated, not freshly installed. */
    private val isUpgrade: () -> Boolean,
) {
    private val releases: List<ReleaseNote> by lazy(loadReleaseNotes)
    private val _badgePending = MutableStateFlow(store.badgePending)

    /** True from when the sheet appears until the Settings history is opened. */
    val badgePending: StateFlow<Boolean> = _badgePending.asStateFlow()

    /** The installed version as MAJOR.MINOR.PATCH, or the raw versionName when it is not one. */
    val installedVersion: String = SemanticVersion.parse(installedVersionName)?.toString() ?: installedVersionName

    private var decided = false
    private var pending: WhatsNewLaunchDecision? = null

    /**
     * Releases the sheet should show in this process, newest first; empty when there is nothing
     * to show or the sheet was already shown. Decides on the first call.
     */
    @Synchronized
    fun pendingReleases(): List<ReleaseNote> {
        if (!decided) {
            decided = true
            val decision =
                WhatsNewLaunchPolicy.decide(
                    installedVersion = installedVersionName,
                    lastSeen = store.lastSeenReleaseNotesVersion,
                    isUpgrade = isUpgrade,
                    releases = releases,
                )
            if (decision.releases.isEmpty()) {
                decision.recordLastSeen?.let { store.lastSeenReleaseNotesVersion = it.toString() }
            } else {
                pending = decision
            }
        }
        return pending?.releases.orEmpty()
    }

    /**
     * The sheet is about to appear: its releases count as seen, and Settings shows the badge.
     * False when they were already seen (the history was opened first), so the sheet stays away.
     */
    @Synchronized
    fun markPresented(): Boolean {
        val decision = pending ?: return false
        pending = null
        decision.recordLastSeen?.let { store.lastSeenReleaseNotesVersion = it.toString() }
        store.badgePending = true
        _badgePending.value = true
        return true
    }

    /**
     * The Settings history was opened: pending releases count as seen there, so the sheet will
     * not show them again, and the badge clears. Blocking on the first decision.
     */
    @Synchronized
    fun markSeenInHistory() {
        pendingReleases()
        pending?.recordLastSeen?.let { store.lastSeenReleaseNotesVersion = it.toString() }
        pending = null
        clearBadge()
    }

    /** Every release up to the installed one, newest first. */
    fun history(): List<ReleaseNote> {
        val installed = SemanticVersion.parse(installedVersionName) ?: return releases
        return releases.upTo(installed)
    }

    /** The Settings history was opened. */
    @Synchronized
    fun clearBadge() {
        if (!_badgePending.value && !store.badgePending) return
        store.badgePending = false
        _badgePending.value = false
    }
}
