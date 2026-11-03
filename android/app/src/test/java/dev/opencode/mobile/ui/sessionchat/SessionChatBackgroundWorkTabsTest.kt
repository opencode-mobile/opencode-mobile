package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Done
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Failed
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Running
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.Stopped
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus.WaitingForInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Running / Done tabs and the row data of the background work panel (#966). */
class SessionChatBackgroundWorkTabsTest {
    @Test
    fun tabs_runningHoldsActiveRowsAndDoneTheRestNewestFirst() {
        val rows =
            listOf(
                row("prt-1", WaitingForInput),
                row("prt-2", Running),
                row("prt-3", Done, finished = START + 10_000),
                row("prt-4", Failed, finished = START + 30_000),
                row("prt-5", Stopped, finished = START + 20_000),
                row("prt-6", Done, finished = null),
                row("prt-7", Done, finished = null),
            )

        val tabs = SessionChatBackgroundWork.tabs(rows)

        assertEquals(listOf("prt-1", "prt-2"), tabs.running.map { it.id })
        assertEquals(listOf("prt-4", "prt-5", "prt-3", "prt-7", "prt-6"), tabs.done.map { it.id })
    }

    @Test
    fun doneDot_firstRowsCountAsSeenAndALaterFinishDots() {
        val first = listOf(row("prt-1", Done, finished = START + 1), row("prt-2", Running))
        val seen = SessionChatBackgroundWork.seenDoneIds(previous = null, subagents = first, viewingDone = false)
        assertFalse(SessionChatBackgroundWork.hasUnseenDone(first, seen))

        val later = listOf(row("prt-1", Done, finished = START + 1), row("prt-2", Done, finished = START + 2))
        val stillSeen = SessionChatBackgroundWork.seenDoneIds(seen, later, viewingDone = false)
        assertTrue(SessionChatBackgroundWork.hasUnseenDone(later, stillSeen))

        val viewed = SessionChatBackgroundWork.seenDoneIds(stillSeen, later, viewingDone = true)
        assertFalse(SessionChatBackgroundWork.hasUnseenDone(later, viewed))
    }

    @Test
    fun doneDot_waitsForTheFirstRowsAndDotsARestartedSubagentAgain() {
        assertNull(SessionChatBackgroundWork.seenDoneIds(previous = null, subagents = emptyList(), viewingDone = false))
        assertFalse(SessionChatBackgroundWork.hasUnseenDone(listOf(row("prt-1", Done)), seenDoneIds = null))

        val done = listOf(row("prt-1", Done))
        val seen = SessionChatBackgroundWork.seenDoneIds(null, done, viewingDone = false)
        val restarted = SessionChatBackgroundWork.seenDoneIds(seen, listOf(row("prt-1", Running)), viewingDone = false)
        val finishedAgain = listOf(row("prt-1", Done))

        assertTrue(SessionChatBackgroundWork.hasUnseenDone(finishedAgain, SessionChatBackgroundWork.seenDoneIds(restarted, finishedAgain, false)))
    }

    @Test
    fun rowText_runningShowsStepCountAndLiveTimer() {
        val subagent = row("prt-1", Running).copy(title = "Find float money math", currentStep = "Searching toFixed", toolCount = 12)

        val text = SessionChatBackgroundWork.rowText(subagent, START + 64_000)

        assertEquals(SessionChatSubagentRowText("Find float money math", null, "Searching toFixed", "12 tools", "1:04"), text)
        assertEquals("1 tool", SessionChatBackgroundWork.toolCountLabel(1))
    }

    @Test
    fun rowText_waitingSaysSoAndLeavesOutMissingBits() {
        val text = SessionChatBackgroundWork.rowText(row("prt-1", WaitingForInput), START + 5_000)

        assertEquals(SessionChatSubagentRowText("general", null, "Waiting for input", null, "0:05"), text)
    }

    @Test
    fun rowText_doneShowsResultDurationAndWhenItFinished() {
        val subagent = row("prt-1", Done, finished = START + 151_000).copy(title = "Map invoice flow", agentTag = "explore", result = "parse → render")

        val text = SessionChatBackgroundWork.rowText(subagent, START + 151_000 + 4 * 60_000)

        assertEquals(SessionChatSubagentRowText("Map invoice flow", "explore", "parse → render", "4m ago", "2:31"), text)
    }

    @Test
    fun rowText_unknownDurationIsHiddenNotZero() {
        val unknownEnd = row("prt-1", Done, finished = null)
        val endAtStart = row("prt-2", Done, finished = START)
        val unknownStart = row("prt-3", Done, finished = START + 9_000).copy(startedAtMs = null)
        val failedAtOnce = row("prt-4", Failed, finished = START + 300)

        assertNull(unknownEnd.elapsed(START + 60_000))
        assertNull(SessionChatBackgroundWork.rowText(unknownEnd, START + 60_000).detailCaption)
        assertNull(endAtStart.elapsed(START + 60_000))
        assertNull(unknownStart.elapsed(START + 60_000))
        assertNull(failedAtOnce.elapsed(START + 60_000))
    }

    @Test
    fun finishTime_ignoresASettleStampedWithTheStart() {
        // A live settle with no running event before it carries the event time as both ends.
        val tool = task("prt-1", ToolStatus.Completed, start = START, end = START)

        val rows = subagents(tool)

        assertNull(rows.single().finishedAtMs)
        assertNull(rows.single().elapsed(START + 60_000))
    }

    @Test
    fun agentTag_onlyForANamedAgentOtherThanGeneral() {
        val rows =
            subagents(
                task("prt-1", ToolStatus.Running, agent = "general"),
                task("prt-2", ToolStatus.Running, agent = "explore"),
                task("prt-3", ToolStatus.Running, agent = null),
            )

        assertEquals(listOf(null, "explore", null), rows.map { it.agentTag })
        assertNull(SessionChatBackgroundWork.agentTag("General"))
        assertNull(SessionChatBackgroundWork.agentTag(" "))
    }

    @Test
    fun v1_classicSummaryGivesStepAndCount() {
        val tool =
            task(
                "prt-1",
                ToolStatus.Running,
                metadata =
                    buildJsonObject {
                        put("sessionId", "ses-a")
                        putJsonArray("summary") {
                            addJsonObject {
                                put("id", "prt-c1")
                                put("tool", "read")
                                putJsonObject("state") {
                                    put("status", "completed")
                                    putJsonObject("input") { put("filePath", "src/format.ts") }
                                }
                            }
                            addJsonObject {
                                put("id", "prt-c2")
                                put("tool", "grep")
                                putJsonObject("state") {
                                    put("status", "running")
                                    putJsonObject("input") { put("pattern", "toFixed") }
                                }
                            }
                        }
                    },
            )

        val row = subagents(tool).single()

        assertEquals(2, row.toolCount)
        assertTrue(row.currentStep.orEmpty().contains("toFixed"))
    }

    @Test
    fun v1_currentResultTagsGiveTheFirstLineAndNoSummaryMeansNoStep() {
        // v1 1.17+: no child summary; the output wraps the reply in <task> and <task_result>.
        val tool =
            task(
                "prt-1",
                ToolStatus.Completed,
                end = START + 30_000,
                metadata = buildJsonObject { put("sessionId", "ses-a") },
                output = "<task id=\"ses-a\" state=\"completed\">\n<task_result>\n## Found 2 spots\n- src/format.ts:9\n</task_result>\n</task>",
            )

        val row = subagents(tool).single()

        assertEquals("Found 2 spots", row.result)
        assertNull(row.currentStep)
        assertNull(row.toolCount)
        assertEquals("0:30", row.elapsed(START + 99_000))
    }

    @Test
    fun v1_failureReasonDropsTheTaskIdLead() {
        val tool =
            task(
                "prt-1",
                ToolStatus.Error,
                end = START + 5_000,
                error = "Subagent failed (task_id: ses-a): Rate limited\nretry later",
            )

        assertEquals("Rate limited", subagents(tool).single().result)
    }

    @Test
    fun v2_subagentBodyGivesTheResultAndCancelReads() {
        val done =
            task(
                "prt-1",
                ToolStatus.Completed,
                name = "subagent",
                end = START + 2_000,
                metadata =
                    buildJsonObject {
                        put("sessionID", "ses-a")
                        put("status", "completed")
                    },
                output = "<subagent sessionID=\"ses-a\" state=\"completed\">\nFound 2 spots:\n- `src/format.ts:9`\n</subagent>",
            )
        val cancelled =
            task(
                "prt-2",
                ToolStatus.Error,
                name = "subagent",
                end = START + 2_000,
                metadata = buildJsonObject { put("sessionID", "ses-b") },
                error = "Subagent cancelled (sessionID: ses-b)",
            )

        val rows = subagents(done, cancelled)

        assertEquals(listOf("Found 2 spots:", "Subagent cancelled"), rows.map { it.result })
        assertEquals(listOf("ses-a", "ses-b"), rows.map { it.childSessionId })
    }

    @Test
    fun v2_backgroundStartNoteIsNotAResult() {
        val tool =
            task(
                "prt-1",
                ToolStatus.Completed,
                name = "subagent",
                end = START + 100,
                metadata =
                    buildJsonObject {
                        put("sessionID", "ses-a")
                        put("status", "running")
                        put("background", true)
                    },
                output = "The subagent is working in the background (sessionID: ses-a).",
            )

        assertNull(subagents(tool).single().result)
    }

    @Test
    fun stoppedWithoutAReasonSaysStopped() {
        val tool = task("prt-1", ToolStatus.Error, end = START + 1_000, interrupted = true)

        val row = subagents(tool).single()

        assertEquals(Stopped, row.status)
        assertEquals("Stopped", row.result)
    }

    private fun subagents(vararg parts: MessagePart) =
        SessionChatBackgroundWork.subagents(
            messages = listOf(Message(id = "msg-1", role = MessageRole.Assistant, text = "", parts = parts.toList())),
            sessions = emptyList(),
            busySessionIds = emptySet(),
            requestSessionIds = emptyList(),
        )

    private fun task(
        id: String,
        status: ToolStatus,
        name: String = "task",
        agent: String? = "general",
        start: Long = START,
        end: Long? = null,
        metadata: JsonObject? = buildJsonObject { put("sessionId", "ses-$id") },
        output: String? = null,
        error: String? = null,
        interrupted: Boolean = false,
    ) = MessagePart.Tool(
        toolName = name,
        status = status,
        id = id,
        input =
            buildJsonObject {
                put("description", "Look around")
                agent?.let { put(if (name == "subagent") "agent" else "subagent_type", it) }
            },
        output = output,
        error = error,
        metadata = metadata,
        timeStartEpochMs = start,
        timeEndEpochMs = end,
        isInterrupted = interrupted,
    )

    private fun row(
        id: String,
        status: SessionChatSubagentStatus,
        finished: Long? = null,
    ) = SessionChatSubagent(
        id = id,
        childSessionId = "ses-$id",
        agentName = "general",
        title = null,
        status = status,
        startedAtMs = START,
        finishedAtMs = finished,
    )

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
