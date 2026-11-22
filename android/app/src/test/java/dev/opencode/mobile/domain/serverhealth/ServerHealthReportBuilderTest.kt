package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState.AuthFailed
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState.Connecting
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState.Live
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState.Offline
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState.Reconnecting
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState.Stopped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHealthReportBuilderTest {
    private val now = 100L * HOUR
    private val start = now - HOUR

    private fun state(
        atMs: Long,
        state: ServerHealthConnectionState,
    ) = ServerHealthEvent.state(PROFILE, atMs, state)

    private fun ping(
        atMs: Long,
        latencyMs: Int?,
        failure: ServerHealthFailureCategory? = null,
    ) = ServerHealthPing(PROFILE, atMs, if (failure == null) latencyMs else null, failure)

    private fun hour(
        events: List<ServerHealthEvent>,
        pings: List<ServerHealthPing> = emptyList(),
    ) = ServerHealthReportBuilder.build(pings, events, now, ServerHealthRange.Hour)

    @Test
    fun uptime_countsOnlyObservedTime() {
        val report =
            hour(
                listOf(
                    state(start, Live),
                    state(start + 10 * MINUTE, Stopped),
                    state(start + 30 * MINUTE, Live),
                    state(start + 50 * MINUTE, Offline),
                ),
            )

        assertEquals(40 * MINUTE, report.observedMs)
        assertEquals(30 * MINUTE, report.liveMs)
        assertEquals(75.0, report.uptimePercent!!, 0.0001)
        assertEquals(1, report.drops)
        assertEquals(30 * MINUTE, report.meanTimeBetweenDropsMs)
        assertNull(report.medianRecoveryMs)
    }

    @Test
    fun uptime_isNullWhenNothingWasObserved() {
        val report = hour(listOf(state(start + MINUTE, Stopped)))

        assertNull(report.uptimePercent)
        assertNull(report.meanTimeBetweenDropsMs)
        assertEquals(0, report.drops)
    }

    @Test
    fun drops_skipLiveToStoppedAndConnectingAfterStopped() {
        val report =
            hour(
                listOf(
                    state(start, Live),
                    state(start + 5 * MINUTE, Stopped),
                    state(start + 10 * MINUTE, Connecting),
                    state(start + 11 * MINUTE, Live),
                    state(start + 20 * MINUTE, Reconnecting),
                    state(start + 21 * MINUTE, Live),
                    state(start + 30 * MINUTE, AuthFailed),
                    state(start + 31 * MINUTE, Live),
                    state(start + 40 * MINUTE, Connecting),
                ),
            )

        assertEquals(3, report.drops)
    }

    @Test
    fun recovery_isExcludedWhenStoppedComesFirst() {
        val report =
            hour(
                listOf(
                    state(start, Live),
                    state(start + 10 * MINUTE, Reconnecting),
                    state(start + 15 * MINUTE, Stopped),
                    state(start + 20 * MINUTE, Live),
                    state(start + 30 * MINUTE, Reconnecting),
                    state(start + 32 * MINUTE, Live),
                ),
            )

        assertEquals(2, report.drops)
        assertEquals(2 * MINUTE, report.medianRecoveryMs)
    }

    @Test
    fun median_averagesTheTwoMiddlesForAnEvenCount() {
        assertEquals(3L, ServerHealthReportBuilder.median(listOf(5L, 1L, 3L)))
        assertEquals(2_000L, ServerHealthReportBuilder.median(listOf(3_000L, 1_000L)))
        assertEquals(2_500L, ServerHealthReportBuilder.median(listOf(1_000L, 2_000L, 3_000L, 4_000L)))
        assertNull(ServerHealthReportBuilder.median(emptyList()))
    }

    @Test
    fun recovery_medianOverOddAndEvenDropCounts() {
        val events =
            listOf(
                state(start, Live),
                state(start + MINUTE, Reconnecting),
                state(start + 2 * MINUTE, Live),
                state(start + 10 * MINUTE, Reconnecting),
                state(start + 13 * MINUTE, Live),
            )

        assertEquals(2 * MINUTE, hour(events).medianRecoveryMs)

        val odd =
            events +
                listOf(
                    state(start + 20 * MINUTE, Offline),
                    state(start + 30 * MINUTE, Live),
                )
        assertEquals(3 * MINUTE, hour(odd).medianRecoveryMs)
    }

    @Test
    fun percentiles_useNearestRank() {
        val values = (1..20).toList()
        assertEquals(10, ServerHealthReportBuilder.percentile(values, 0.5))
        assertEquals(19, ServerHealthReportBuilder.percentile(values, 0.95))
        assertEquals(30, ServerHealthReportBuilder.percentile(listOf(30, 10, 20), 0.95))
        assertEquals(20, ServerHealthReportBuilder.percentile(listOf(30, 10, 20), 0.5))
        assertEquals(5, ServerHealthReportBuilder.percentile(listOf(5), 0.95))
        assertNull(ServerHealthReportBuilder.percentile(emptyList(), 0.5))
    }

    @Test
    fun latencyAndErrorRate_comeFromChecksInRange() {
        val pings =
            listOf(
                ping(start - MINUTE, 999),
                ping(start + MINUTE, 10),
                ping(start + 2 * MINUTE, 20),
                ping(start + 3 * MINUTE, 30),
                ping(start + 4 * MINUTE, null, ServerHealthFailureCategory.Timeout),
            )

        val report = hour(listOf(state(start, Live)), pings)

        assertEquals(4, report.checks)
        assertEquals(1, report.failedChecks)
        assertEquals(25.0, report.errorRatePercent!!, 0.0001)
        assertEquals(20, report.p50LatencyMs)
        assertEquals(30, report.p95LatencyMs)
    }

    @Test
    fun buckets_treatConnectingAsNeutralAndMostlyTroubleAsDown() {
        val second = 1_000L
        val report =
            hour(
                listOf(
                    state(start, Connecting),
                    state(start + 60 * second, Live),
                    state(start + 80 * second, Reconnecting),
                    state(start + 140 * second, Live),
                    state(start + 180 * second, Stopped),
                ),
            )

        assertEquals(60, report.buckets.size)
        assertEquals(ServerHealthBucketCondition.Live, report.buckets[0].condition)
        assertEquals(ServerHealthBucketCondition.Down, report.buckets[1].condition)
        assertEquals(1, report.buckets[1].drops)
        assertEquals(ServerHealthBucketCondition.Degraded, report.buckets[2].condition)
        assertEquals(ServerHealthBucketCondition.Unobserved, report.buckets[3].condition)
    }

    @Test
    fun buckets_degradeOnADropWithoutTroubleTime() {
        val report =
            hour(
                listOf(
                    state(start, Live),
                    state(start + 30_000, Connecting),
                    state(start + 31_000, Live),
                ),
            )

        assertEquals(ServerHealthBucketCondition.Degraded, report.buckets[0].condition)
        assertEquals(ServerHealthBucketCondition.Live, report.buckets[1].condition)
    }

    @Test
    fun bucketCounts_matchEachRange() {
        assertEquals(96, ServerHealthReportBuilder.build(emptyList(), emptyList(), now, ServerHealthRange.Day).buckets.size)
        assertEquals(84, ServerHealthReportBuilder.build(emptyList(), emptyList(), now, ServerHealthRange.Week).buckets.size)
    }

    @Test
    fun failuresByCategory_mergesStreamFailuresAndFailedChecks() {
        val events =
            listOf(
                state(start, Live),
                ServerHealthEvent.failure(PROFILE, start + MINUTE, ServerHealthFailureCategory.Heartbeat),
                ServerHealthEvent.failure(PROFILE, start + 2 * MINUTE, ServerHealthFailureCategory.Heartbeat),
                ServerHealthEvent.failure(PROFILE, start - MINUTE, ServerHealthFailureCategory.Auth),
            )
        val pings =
            listOf(
                ping(start + MINUTE, null, ServerHealthFailureCategory.Network),
                ping(start + 2 * MINUTE, null, ServerHealthFailureCategory.Timeout),
                ping(start + 3 * MINUTE, null, ServerHealthFailureCategory.Timeout),
            )

        val report = hour(events, pings)

        assertEquals(
            listOf(
                ServerHealthFailureCount(ServerHealthFailureCategory.Timeout, 2),
                ServerHealthFailureCount(ServerHealthFailureCategory.Heartbeat, 2),
                ServerHealthFailureCount(ServerHealthFailureCategory.Network, 1),
            ),
            report.failuresByCategory,
        )
    }

    @Test
    fun initialState_comesFromTheLatestStateBeforeTheRange() {
        val report =
            hour(
                listOf(
                    state(start - 3 * HOUR, Offline),
                    state(start - 10 * MINUTE, Live),
                ),
            )

        assertEquals(HOUR, report.observedMs)
        assertEquals(100.0, report.uptimePercent!!, 0.0001)
        assertEquals(0, report.drops)
    }

    @Test
    fun restarts_inRangeAndLastRestartAcrossAllData() {
        val events =
            listOf(
                ServerHealthEvent.restart(PROFILE, start - 2 * HOUR, "1.0.1", "1.0.0"),
                ServerHealthEvent.restart(PROFILE, start + MINUTE, "1.0.2", "1.0.1"),
                ServerHealthEvent.restart(PROFILE, start + 2 * MINUTE, null, "1.0.2"),
            )

        val report = hour(events)

        assertEquals(
            listOf(
                ServerHealthRestart(start + MINUTE, "1.0.2", "1.0.1"),
                ServerHealthRestart(start + 2 * MINUTE, null, "1.0.2"),
            ),
            report.restarts,
        )
        assertEquals(start + 2 * MINUTE, report.lastRestartAtMs)

        assertEquals(start - 2 * HOUR, hour(events.take(1)).lastRestartAtMs)
    }

    @Test
    fun isEmpty_onlyWithNoDataAtAll() {
        assertTrue(hour(emptyList()).isEmpty)
        assertFalse(hour(listOf(state(start - 5 * HOUR, Stopped))).isEmpty)
    }

    @Test
    fun recentPings_areChronologicalWithFailuresAsNull() {
        val pings =
            listOf(
                ping(now - 40 * MINUTE, 99),
                ping(now - MINUTE, 12),
                ping(now - 2 * MINUTE, null, ServerHealthFailureCategory.Network),
                ping(now - 3 * MINUTE, 30),
            )

        assertEquals(listOf(30, null, 12), ServerHealthReportBuilder.recentPings(pings, now))
    }

    @Test
    fun openBuckets_dropClosedEndsAndShrinkGapsToOne() {
        val report =
            hour(
                listOf(
                    state(start + 10 * MINUTE, Live),
                    state(start + 12 * MINUTE, Stopped),
                    state(start + 30 * MINUTE, Live),
                    state(start + 32 * MINUTE, Stopped),
                ),
            )

        assertEquals(
            listOf(
                ServerHealthBucketCondition.Live,
                ServerHealthBucketCondition.Live,
                ServerHealthBucketCondition.Unobserved,
                ServerHealthBucketCondition.Live,
                ServerHealthBucketCondition.Live,
            ),
            report.openBuckets.map { it.condition },
        )
        assertEquals(start + 10 * MINUTE, report.openBuckets.first().startMs)
    }

    @Test
    fun openBuckets_emptyWhenAppWasNeverOpen() {
        assertTrue(hour(listOf(state(start + MINUTE, Stopped))).openBuckets.isEmpty())
    }

    @Test
    fun errorLog_mergesConsecutiveFailedChecksNewestFirst() {
        val report =
            hour(
                listOf(ServerHealthEvent.failure(PROFILE, start + 20 * MINUTE, ServerHealthFailureCategory.Heartbeat)),
                listOf(
                    ping(start + 10 * MINUTE, null, ServerHealthFailureCategory.Network),
                    ping(start + 10 * MINUTE + 30_000, null, ServerHealthFailureCategory.Network),
                    ping(start + 11 * MINUTE, 20),
                    ping(start + 11 * MINUTE + 30_000, null, ServerHealthFailureCategory.Network),
                ),
            )

        assertEquals(
            listOf(
                ServerHealthErrorSource.Stream to 1,
                ServerHealthErrorSource.Check to 1,
                ServerHealthErrorSource.Check to 2,
            ),
            report.errorLog.map { it.source to it.count },
        )
        assertEquals(start + 10 * MINUTE, report.errorLog.last().firstAtMs)
        assertEquals(start + 10 * MINUTE + 30_000, report.errorLog.last().lastAtMs)
        assertEquals(3, report.errorLogTotal)
    }

    @Test
    fun errorLog_splitsFailuresAcrossAClosedAppGap() {
        val report =
            hour(
                emptyList(),
                listOf(
                    ping(start + 10 * MINUTE, null, ServerHealthFailureCategory.Timeout),
                    ping(start + 30 * MINUTE, null, ServerHealthFailureCategory.Timeout),
                ),
            )

        assertEquals(listOf(1, 1), report.errorLog.map { it.count })
    }

    private companion object {
        const val PROFILE = "profile-a"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
