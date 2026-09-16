package dev.opencode.mobile.domain.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MainThreadHangWatchdogTest {
    @Test
    fun detectsHangWhenAcknowledgmentIsOverdue() {
        val detector = MainThreadHangDetector()
        detector.notePingSent(atEpochMilliseconds = 1_000L)
        val event =
            detector.evaluate(
                nowEpochMilliseconds = 1_000L + 2_500L,
                thresholdMilliseconds = 2_500L,
            )
        assertEquals(MainThreadHangDetector.Event.HangDetected(2_500L), event)
        assertTrue(detector.isHung)
    }

    @Test
    fun doesNotSpamWhileStillHung() {
        val detector = MainThreadHangDetector()
        detector.notePingSent(atEpochMilliseconds = 0L)
        assertTrue(
            detector.evaluate(
                nowEpochMilliseconds = 3_000L,
                thresholdMilliseconds = 2_500L,
            ) is MainThreadHangDetector.Event.HangDetected,
        )
        assertNull(
            detector.evaluate(
                nowEpochMilliseconds = 4_000L,
                thresholdMilliseconds = 2_500L,
            ),
        )
        assertTrue(detector.isHung)
    }

    @Test
    fun recoversAfterAcknowledgment() {
        val detector = MainThreadHangDetector()
        detector.notePingSent(atEpochMilliseconds = 100L)
        detector.evaluate(
            nowEpochMilliseconds = 3_000L,
            thresholdMilliseconds = 2_500L,
        )
        val recovered = detector.notePingAcknowledged(atEpochMilliseconds = 4_100L)
        assertEquals(MainThreadHangDetector.Event.HangRecovered(4_000L), recovered)
        assertFalse(detector.isHung)
        assertFalse(detector.hasPendingPing)
    }

    @Test
    fun acknowledgmentWithoutHangIsSilent() {
        val detector = MainThreadHangDetector()
        detector.notePingSent(atEpochMilliseconds = 50L)
        assertNull(detector.notePingAcknowledged(atEpochMilliseconds = 60L))
        assertFalse(detector.hasPendingPing)
    }

    @Test
    fun watchdogLogsDetectAndRecoverThroughEventSink() {
        var now = 0L
        var pendingMainWork: Runnable? = null
        val events = CopyOnWriteArrayList<MainThreadHangDetector.Event>()

        val watchdog =
            MainThreadHangWatchdog.forTests(
                clockEpochMilliseconds = { now },
                scheduleOnMain = { runnable -> pendingMainWork = runnable },
                onEvent = { events += it },
                scheduler = Executors.newSingleThreadScheduledExecutor(),
            )

        watchdog.tickForTesting()
        assertTrue(pendingMainWork != null)
        assertTrue(events.isEmpty())

        now = MainThreadHangWatchdog.DETECT_THRESHOLD_MILLISECONDS
        watchdog.tickForTesting()
        assertEquals(
            listOf(
                MainThreadHangDetector.Event.HangDetected(
                    MainThreadHangWatchdog.DETECT_THRESHOLD_MILLISECONDS,
                ),
            ),
            events.toList(),
        )

        now = MainThreadHangWatchdog.DETECT_THRESHOLD_MILLISECONDS + 250L
        pendingMainWork!!.run()
        assertEquals(
            listOf(
                MainThreadHangDetector.Event.HangDetected(
                    MainThreadHangWatchdog.DETECT_THRESHOLD_MILLISECONDS,
                ),
                MainThreadHangDetector.Event.HangRecovered(
                    MainThreadHangWatchdog.DETECT_THRESHOLD_MILLISECONDS + 250L,
                ),
            ),
            events.toList(),
        )

        watchdog.stop()
    }

    @Test
    fun hangMessagesStayWithinRedactionRules() {
        val detected = DiagnosticLogRedactor.redact("hang detected overdueMs=2500")
        val recovered = DiagnosticLogRedactor.redact("hang recovered durationMs=4000")
        assertEquals("hang detected overdueMs=2500", detected)
        assertEquals("hang recovered durationMs=4000", recovered)
        assertFalse(detected.lowercase().contains("password"))
        assertFalse(recovered.lowercase().contains("token"))
    }
}
