package dev.opencode.mobile.domain.featureflags

/** Where a resolved flag value came from. */
enum class FeatureFlagSource {
    Default,
    Remote,
    Override,
}

data class FeatureFlagResolution(
    val value: Boolean,
    val source: FeatureFlagSource,
)

/**
 * Pure resolution order for one flag (OPE-269): developer override on device,
 * then the activated Remote Config value, then the bundled default.
 */
object FeatureFlagResolutionPolicy {
    fun resolve(
        override: Boolean?,
        remote: Boolean?,
        default: Boolean,
    ): FeatureFlagResolution =
        when {
            override != null -> FeatureFlagResolution(override, FeatureFlagSource.Override)
            remote != null -> FeatureFlagResolution(remote, FeatureFlagSource.Remote)
            else -> FeatureFlagResolution(default, FeatureFlagSource.Default)
        }
}
