package dev.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsVerificationPolicyTest {
    @Test
    fun `disabled builds never record verification`() {
        assertFalse(
            CrashlyticsVerificationPolicy.shouldRecord(
                enabled = false,
                alreadyRecorded = false,
            ),
        )
    }

    @Test
    fun `enabled builds record verification only before the marker exists`() {
        assertTrue(
            CrashlyticsVerificationPolicy.shouldRecord(
                enabled = true,
                alreadyRecorded = false,
            ),
        )
        assertFalse(
            CrashlyticsVerificationPolicy.shouldRecord(
                enabled = true,
                alreadyRecorded = true,
            ),
        )
    }

    @Test
    fun `marker is stable and version scoped`() {
        assertEquals(
            "crashlytics_verified_42",
            CrashlyticsVerificationPolicy.markerKey(versionCode = 42),
        )
    }
}
