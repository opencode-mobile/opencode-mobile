package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.DIR
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.GLOBAL
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.GLOBAL_FILE
import dev.opencode.hexcode.session.FakeArtifactsApi.Companion.configOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The enable and turn-off flow (app-plan.md, Artifacts (M8); artifacts-enable-spike.md §3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactSetupFlowTest {
    private val model = ModelChoice("opencode", "beam-free")
    private val text = "{\n  // mine\n  \"experimental\": { \"todo\": true },\n}\n".toByteArray()
    private val beforeInfo = """{"model":"x","experimental":{"todo":true}}"""
    private val onInfo = """{"model":"x","experimental":{"todo":true,"artifacts":true}}"""

    private val api =
        FakeArtifactsApi().apply {
            config = configOf(GLOBAL_FILE to beforeInfo)
            files[GLOBAL_FILE] = text
        }
    private val store = MemoryArtifactsStore()
    private val applied = mutableListOf<SetupMode>()

    private fun TestScope.flow() = ArtifactSetupFlow(backgroundScope, api, store, { DIR }, { model }, onApplied = { applied += it })

    private fun ArtifactSetupFlow.now() = state.value!!

    private fun line(
        type: String,
        data: String,
    ) = """{"type":"$type","data":$data}"""

    private fun approval(session: String) =
        line(
            "permission.asked",
            """{"id":"per_1","sessionID":"$session","action":"edit","resources":["$GLOBAL_FILE"],"save":["*"],""" +
                """"metadata":{"files":[{"file":"$GLOBAL_FILE","patch":"@@ -1 +1 @@\n-a\n+b\n","additions":1,"deletions":1}]}}""",
        )

    @Test
    fun explainsTheFileAndTheExactPromptBeforeSendingAnything() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            val now = flow.now()
            assertEquals(SetupStage.Explain, now.stage)
            assertEquals(ConfigTarget.File(GLOBAL_FILE, hasBlock = true, global = true), now.target)
            assertEquals(ArtifactPrompts.turnOn(GLOBAL_FILE), now.prompt)
            assertEquals(false, now.reused)
            assertTrue(api.prompts.isEmpty())
            assertTrue(api.created.isEmpty())
        }

    @Test
    fun aFileThatAlreadyDoesntParseIsNeverSentThePrompt() =
        runTest {
            api.config = configOf()
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            assertEquals(SetupStage.Blocked(SetupStage.Blocked.Why.Broken), flow.now().stage)
            flow.send()
            runCurrent()
            assertTrue(api.prompts.isEmpty())
        }

    @Test
    fun theEnvironmentCanOnlyBeChangedByHand() =
        runTest {
            api.config = configOf(GLOBAL_FILE to "{}", null to """{"experimental":{}}""")
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            assertEquals(SetupStage.Blocked(SetupStage.Blocked.Why.Env), flow.now().stage)
            assertEquals("\"artifacts\": true", flow.now().target?.line)
        }

    @Test
    fun aChangedRouteIsNotAvailable() =
        runTest {
            api.list = ArtifactListAnswer.Changed
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            assertEquals(SetupStage.Blocked(SetupStage.Blocked.Why.Unavailable), flow.now().stage)
        }

    @Test
    fun alreadyOnIsDone() =
        runTest {
            api.list = ArtifactListAnswer.On(emptyList())
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            assertEquals(SetupStage.Done, flow.now().stage)
            assertEquals(listOf(SetupMode.On), applied)
        }

    @Test
    fun theSetupSessionIsMadeOnceWithTheLockdownAndReused() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            val (dir, chosen, rules) = api.created.single()
            assertEquals(DIR, dir)
            assertEquals(model, chosen)
            assertEquals(ArtifactPrompts.lockdown(GLOBAL), rules)
            assertEquals("ses_setup1" to ArtifactPrompts.turnOn(GLOBAL_FILE), api.prompts.single())
            assertEquals("ses_setup1" to DIR, store.session)
            flow.close()
            flow.close()

            val again = flow()
            again.start(SetupMode.On)
            runCurrent()
            assertEquals(true, again.now().reused)
            again.send()
            runCurrent()
            assertEquals(1, api.created.size)
            assertEquals("ses_setup1", api.prompts.last().first)
        }

    @Test
    fun aSetupSessionThatWentIsMadeAgain() =
        runTest {
            store.session = "ses_gone" to DIR
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            assertEquals(1, api.created.size)
            assertEquals("ses_setup1", api.prompts.single().first)
        }

    @Test
    fun theTwoSecondCheckTurnsItOnBeforeTheAgentReplies() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            assertEquals(SetupStage.Running, flow.now().stage)
            assertEquals(SetupStepState.Done, flow.now().steps.sent)
            assertEquals(SetupStepState.Doing, flow.now().steps.check)
            val calls = api.listCalls
            advanceTimeBy(2_001)
            assertEquals(calls + 1, api.listCalls)
            assertEquals(SetupStage.Running, flow.now().stage)
            // The file is changed on the computer: the next check sees it.
            api.config = configOf(GLOBAL_FILE to onInfo)
            api.list = ArtifactListAnswer.On(emptyList())
            advanceTimeBy(2_000)
            assertEquals(SetupStage.Done, flow.now().stage)
            assertEquals(SetupStepState.Done, flow.now().steps.end)
            assertEquals(listOf(SetupMode.On), applied)
            // The check stops once it's done.
            val done = api.listCalls
            advanceTimeBy(10_000)
            assertEquals(done, api.listCalls)
        }

    @Test
    fun theAgentsWordsAreNeverTheStatus() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.decode(line("session.text.ended", """{"sessionID":"ses_setup1","text":"DONE"}"""))!!)
            flow.event(ArtifactEvent.decode(line("session.execution.succeeded", """{"sessionID":"ses_setup1"}"""))!!)
            runCurrent()
            // Still 403: not on, whatever the reply.
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.StillOff), flow.now().stage)
            assertEquals(listOf("DONE"), flow.now().replies)
            assertTrue(applied.isEmpty())
        }

    @Test
    fun aFailedReplyGivesTheAgentsReason() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.Reply("ses_setup1", "FAILED: the file is generated by a script"))
            flow.event(ArtifactEvent.RunEnded("ses_setup1"))
            runCurrent()
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.Failed, "the file is generated by a script"), flow.now().stage)
        }

    @Test
    fun theFreeLimitFallsBackToTheLine() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(
                ArtifactEvent.decode(
                    line(
                        "session.execution.failed",
                        """{"sessionID":"ses_setup1","error":{"type":"provider.free-limit","message":"Free usage limit reached","status":429}}""",
                    ),
                )!!,
            )
            runCurrent()
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.FreeLimit, "Free usage limit reached"), flow.now().stage)
            assertEquals(SetupStepState.Failed, flow.now().steps.work)
            // The check keeps running, so adding the line by hand flips it to On.
            api.config = configOf(GLOBAL_FILE to onInfo)
            api.list = ArtifactListAnswer.On(emptyList())
            advanceTimeBy(2_001)
            assertEquals(SetupStage.Done, flow.now().stage)
        }

    @Test
    fun eventsOfOtherSessionsChangeNothing() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.decode(approval("ses_today"))!!)
            flow.event(ArtifactEvent.RunEnded("ses_today", ArtifactEvent.RunError("x", "boom")))
            flow.event(ArtifactEvent.Reply("ses_today", "hello"))
            assertNull(flow.now().approval)
            assertEquals(SetupStage.Running, flow.now().stage)
            assertTrue(flow.now().replies.isEmpty())
        }

    @Test
    fun theLiveLineFollowsTheAgentsTools() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.RunStarted("ses_setup1"))
            assertEquals(SetupActivity.Thinking, flow.now().activity)
            flow.event(ArtifactEvent.ToolNamed("ses_setup1", "c1", "read"))
            flow.event(ArtifactEvent.ToolCalled("ses_setup1", "c1", GLOBAL_FILE))
            assertEquals(SetupActivity.Reading(GLOBAL_FILE), flow.now().activity)
            flow.event(ArtifactEvent.ToolNamed("ses_setup1", "c2", "edit"))
            flow.event(ArtifactEvent.ToolCalled("ses_setup1", "c2", GLOBAL_FILE))
            assertEquals(SetupActivity.Editing(GLOBAL_FILE), flow.now().activity)
        }

    @Test
    fun theApprovalOffersOnlyAllowOnceAndRejectAndPausesTheTimeout() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.decode(approval("ses_setup1"))!!)
            val waiting = flow.now()
            assertEquals("per_1", waiting.approval?.id)
            assertEquals(SetupStepState.Warn, waiting.steps.approval)
            assertEquals(SetupStepState.Dimmed, waiting.steps.work)
            assertEquals(
                GLOBAL_FILE,
                waiting.approval
                    ?.approval
                    ?.files
                    ?.single()
                    ?.path,
            )
            // A person thinks for longer than the timeout: the flow waits.
            advanceTimeBy(90_000)
            assertEquals(SetupStage.Running, flow.now().stage)
            flow.allowOnce()
            runCurrent()
            assertEquals(Triple("ses_setup1", "per_1", true), api.replies.single())
            assertNull(flow.now().approval)
            assertEquals(SetupStepState.Done, flow.now().steps.approval)
        }

    @Test
    fun rejectLeavesTheFileAndShowsTheLineWhileTheCheckGoesOn() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.decode(approval("ses_setup1"))!!)
            flow.reject()
            runCurrent()
            assertEquals(Triple("ses_setup1", "per_1", false), api.replies.single())
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.Rejected), flow.now().stage)
            assertEquals(SetupStepState.Failed, flow.now().steps.approval)
            // The run's end after a reject doesn't replace the fallback.
            flow.event(ArtifactEvent.RunEnded("ses_setup1"))
            runCurrent()
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.Rejected), flow.now().stage)
            val calls = api.listCalls
            advanceTimeBy(4_001)
            assertEquals(calls + 2, api.listCalls)
        }

    @Test
    fun sixtySecondsWithoutAResultFallsBack() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            advanceTimeBy(59_000)
            assertEquals(SetupStage.Running, flow.now().stage)
            advanceTimeBy(2_000)
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.TimedOut), flow.now().stage)
        }

    @Test
    fun keysLostOutsideExperimentalAreDamageThatRestoreUndoesByteForByte() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            api.config = configOf(GLOBAL_FILE to """{"experimental":{"todo":true,"artifacts":true}}""")
            api.list = ArtifactListAnswer.On(emptyList())
            advanceTimeBy(2_001)
            assertEquals(SetupStage.Damaged(ConfigDamage(missing = listOf("model"))), flow.now().stage)
            // The server reads the restored file again.
            api.afterWrite = { _, _ ->
                api.config = configOf(GLOBAL_FILE to beforeInfo)
                api.list = ArtifactListAnswer.Off
            }
            flow.restore()
            runCurrent()
            val (path, bytes) = api.writes.single()
            assertEquals(GLOBAL_FILE, path)
            assertArrayEquals(text, bytes)
            assertEquals(SetupStage.Fallback(SetupStage.Fallback.Why.Restored), flow.now().stage)
        }

    @Test
    fun anEditThatBreaksTheFileIsCaughtWhileStillOff() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.ToolNamed("ses_setup1", "c1", "edit"))
            flow.event(ArtifactEvent.ToolEnded("ses_setup1", "c1", failed = false))
            api.config = configOf()
            advanceTimeBy(2_001)
            assertEquals(SetupStage.Damaged(ConfigDamage(unreadable = true)), flow.now().stage)
        }

    @Test
    fun aRestoreThatFailsSaysSo() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            api.config = configOf()
            flow.event(ArtifactEvent.RunEnded("ses_setup1"))
            runCurrent()
            assertTrue(flow.now().stage is SetupStage.Damaged)
            api.writeOk = false
            flow.restore()
            runCurrent()
            assertEquals(true, flow.now().restoreFailed)
            assertTrue(flow.now().stage is SetupStage.Damaged)
        }

    @Test
    fun turningOffSendsTheDisablePrompt() =
        runTest {
            api.config = configOf(GLOBAL_FILE to onInfo)
            api.list = ArtifactListAnswer.On(emptyList())
            val flow = flow()
            flow.start(SetupMode.Off)
            runCurrent()
            assertEquals(ArtifactPrompts.turnOff(GLOBAL_FILE), flow.now().prompt)
            flow.send()
            runCurrent()
            api.config = configOf(GLOBAL_FILE to beforeInfo)
            api.list = ArtifactListAnswer.Off
            advanceTimeBy(2_001)
            assertEquals(SetupStage.Done, flow.now().stage)
            assertEquals(listOf(SetupMode.Off), applied)
        }

    @Test
    fun hidingKeepsItRunningAndCloseOnlyHidesWhileItRuns() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.close()
            assertEquals(true, flow.now().hidden)
            assertEquals(SetupStage.Running, flow.now().stage)
            flow.start(SetupMode.On)
            assertEquals(false, flow.now().hidden)
            assertEquals(1, api.prompts.size)
        }

    @Test
    fun cancelBeforeSendingSendsNothing() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.close()
            assertNull(flow.state.value)
            assertTrue(api.prompts.isEmpty())
        }

    @Test
    fun tryAgainSendsTheSamePromptToTheSameSession() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            flow.event(ArtifactEvent.RunEnded("ses_setup1", ArtifactEvent.RunError("provider.free-limit", "limit", 429)))
            runCurrent()
            flow.tryAgain()
            runCurrent()
            assertEquals(SetupStage.Running, flow.now().stage)
            assertEquals(2, api.prompts.size)
            assertEquals(api.prompts[0], api.prompts[1])
            assertEquals(1, api.created.size)
        }

    @Test
    fun anApprovalMissedWhileTheStreamWasDownIsReadOnReconnect() =
        runTest {
            val flow = flow()
            flow.start(SetupMode.On)
            runCurrent()
            flow.send()
            runCurrent()
            api.approvals = listOf((ArtifactEvent.decode(approval("ses_setup1")) as ArtifactEvent.Approval).item)
            flow.reconnected()
            runCurrent()
            assertEquals("per_1", flow.now().approval?.id)
        }
}
