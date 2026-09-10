package dev.opencode.mobile.domain.recovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseRecoveryFaultOracleTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun loader_decodesAllSharedTraces() {
        val traces = SseFaultOracleLoader.loadAllTraces()
        assertEquals(SseFaultOracleLoader.traceIds, traces.map { it.id })
        traces.forEach { trace ->
            assertTrue(trace.steps.isNotEmpty())
            assertTrue(trace.expected.snapshots.isNotEmpty())
            assertTrue(trace.seed.profileId.isNotBlank())
            assertTrue(trace.seed.sessionId.isNotBlank())
        }
    }

    @Test
    fun loader_schemaDeclaresTraceAndExpectedContracts() {
        val traceSchema = SseFaultOracleLoader.loadSchemaObject("trace.schema.json")
        val expectedSchema = SseFaultOracleLoader.loadSchemaObject("expected.schema.json")
        assertEquals(
            listOf("id", "surface", "protocol", "seed", "steps", "expected"),
            SseFaultOracleLoader.schemaRequiredFields(traceSchema),
        )
        assertEquals(listOf("snapshots"), SseFaultOracleLoader.schemaRequiredFields(expectedSchema))
        assertTrue(traceSchema.containsKey("\$defs"))
        assertTrue(expectedSchema.containsKey("\$defs"))
    }

    @Test
    fun briefBackgroundSameEpoch_doesNotLatchStale() =
        runOracle("android-brief-background-same-epoch-stale-latch")

    @Test
    fun coldResumeDoubleEpoch_performsOneCatchUp() = runOracle("cold-resume-double-epoch")

    @Test
    fun profileSwitch_retargetsStreamAndDropsOldPublication() =
        runOracle("ios-profile-a-to-b-old-stream")

    @Test
    fun v1SilentInstance_isNotKeptLiveByGlobalHeartbeats() =
        runOracle("v1-one-target-silent-while-other-heartbeats")

    @Test
    fun addProjectWhileV1Live_opensNewInstanceStream() = runOracle("add-project-while-v1-live")

    @Test
    fun v1ToV2ProbeWhileOccupying_retargetsRequiredTargets() = runOracle("v1-to-v2-probe-while-occupying")

    @Test
    fun removeProjectWhileV1Live_cancelsOnlyRemovedLease() = runOracle("remove-project-while-v1-live")

    @Test
    fun gapDuringInitialRest_requiresSecondReconciliation() = runOracle("gap-during-initial-rest")

    @Test
    fun profileAToBToA_doesNotPublishCrossProfileEvents() = runOracle("profile-a-to-b-to-a")

    @Test
    fun restSlowSseWins_doesNotOverwriteLivePatch() = runOracle("rest-slow-sse-wins")

    @Test
    fun restFailRetriesSameGap_staysUnsyncedUntilSuccess() = runOracle("rest-fail-retries-same-gap")

    @Test
    fun lateSubscribeWithOpenGap_reconcilesOnce() = runOracle("late-subscribe-open-gap")

    @Test
    fun clearCache_dropsOldObservationAndReconciles() = runOracle("clear-cache-drops-old-observation")

    @Test
    fun parseFailure_opensOneGapAndReconciles() = runOracle("sse-parse-failed-opens-gap")

    @Test
    fun v1InstanceLeaseLost_recoversWithOneReopen() = runOracle("v1-instance-lease-lost-then-recovered")

    @Test
    fun v1GrowthThenIncumbentDeath_opensOneLeaseLost() = runOracle("v1-growth-then-incumbent-lease-lost")

    @Test
    fun oldGenerationWritableChannel_doesNotPublish() = runOracle("old-generation-stays-writable")

    @Test
    fun pathLostThenPathReady_reconcilesOnce() = runOracle("path-lost-then-path-ready")

    @Test
    fun backgroundStopThenForeground_reconcilesOnce() = runOracle("background-stop-then-foreground")

    private fun runOracle(id: String) {
        val trace = SseFaultOracleLoader.loadTrace(id)
        if (trace.surface == "transport") {
            SseFaultOracleTransportDriver().run(trace)
        } else {
            runTest(testDispatcher) {
                SseFaultOracleDriver(this).run(trace)
            }
        }
    }
}
