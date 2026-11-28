package dev.opencode.mobile

import dev.opencode.testguard.TestIsolationGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTestIsolationProbesTest {
    @Test
    fun aSwappedProductAnalyticsSinkIsReportedAndRestored() {
        val productionSink = ProductAnalytics.sink
        val (_, violations) =
            TestIsolationGuard.capturingViolations {
                ProductAnalytics.sink = { _, _ -> }
                TestIsolationGuard.containerFinished("SinkSampleTest")
            }

        assertEquals(1, violations.size)
        assertTrue(violations.single(), violations.single().contains("SinkSampleTest leaked ProductAnalytics.sink = a test sink"))
        assertSame(productionSink, ProductAnalytics.sink)
    }
}
