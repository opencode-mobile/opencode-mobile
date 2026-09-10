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
