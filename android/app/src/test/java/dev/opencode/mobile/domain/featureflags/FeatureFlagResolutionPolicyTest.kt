package dev.opencode.mobile.domain.featureflags

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureFlagResolutionPolicyTest {
    @Test
    fun `default wins when nothing else is set`() {
        assertEquals(
            FeatureFlagResolution(value = true, source = FeatureFlagSource.Default),
            FeatureFlagResolutionPolicy.resolve(override = null, remote = null, default = true),
        )
    }

    @Test
    fun `remote wins over default`() {
        assertEquals(
            FeatureFlagResolution(value = false, source = FeatureFlagSource.Remote),
            FeatureFlagResolutionPolicy.resolve(override = null, remote = false, default = true),
        )
    }

    @Test
    fun `override wins over remote and default`() {
        assertEquals(
            FeatureFlagResolution(value = true, source = FeatureFlagSource.Override),
            FeatureFlagResolutionPolicy.resolve(override = true, remote = false, default = false),
        )
    }
}
