package dev.opencode.mobile.domain.featureflags

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Feature flag store (OPE-269). Holds the remote snapshot for this session,
 * device-local developer overrides, and resolves each [FeatureFlag] through
 * [FeatureFlagResolutionPolicy]. Overrides are cleared by Clear All Data.
 *
 * The remote snapshot is applied once per launch by the release-observability
 * seam after Remote Config activation, so a flag never flips mid-session.
 */
object FeatureFlags {
    private const val PREFS_NAME = "opencode_feature_flags"
    private const val OVERRIDE_KEY_PREFIX = "override_"

    private val remote = mutableStateMapOf<FeatureFlag, Boolean>()
    private val overrides = mutableStateMapOf<FeatureFlag, Boolean>()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        FeatureFlag.entries.forEach { flag ->
            val key = overrideKey(flag)
            if (prefs.contains(key)) {
                overrides[flag] = prefs.getBoolean(key, flag.defaultValue)
            }
        }
    }

    /** Replaces the remote snapshot for this session. Unknown keys are ignored. */
    fun applyRemote(values: Map<String, Boolean>) {
        remote.clear()
        values.forEach { (key, value) ->
            FeatureFlag.fromKey(key)?.let { remote[it] = value }
        }
    }

    fun isEnabled(flag: FeatureFlag): Boolean = resolution(flag).value

    fun resolution(flag: FeatureFlag): FeatureFlagResolution =
        FeatureFlagResolutionPolicy.resolve(
            override = overrides[flag],
            remote = remote[flag],
            default = flag.defaultValue,
        )

    fun override(flag: FeatureFlag): Boolean? = overrides[flag]

    fun setOverride(
        flag: FeatureFlag,
        value: Boolean?,
    ) {
        if (value == null) {
            overrides.remove(flag)
            prefs()?.edit()?.remove(overrideKey(flag))?.apply()
        } else {
            overrides[flag] = value
            prefs()?.edit()?.putBoolean(overrideKey(flag), value)?.apply()
        }
    }

    fun clearOverrides() {
        val editor = prefs()?.edit()
        FeatureFlag.entries.forEach { editor?.remove(overrideKey(it)) }
        editor?.apply()
        overrides.clear()
    }

    private fun overrideKey(flag: FeatureFlag) = OVERRIDE_KEY_PREFIX + flag.key

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
