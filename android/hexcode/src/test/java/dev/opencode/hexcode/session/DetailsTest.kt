package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Details' sections and rows (app-plan.md, Needs you and control › Details), from tool parts captured live on v2.2.5. */
class DetailsTest {
    private fun part(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun tool(
        name: String,
        state: String,
        completed: Long = 1_000,
    ) = """{"type":"tool","id":"call-$completed","name":"$name","state":$state,"time":{"created":1,"ran":2,"completed":$completed}}"""

    private fun step(
        name: String,
        state: String,
    ) = RecentSteps.step(part(tool(name, state)))

    @Test
    fun editsSayEditedCreatedOrHowManyFiles() {
        val edited =
            step(
                "edit",
                """{"status":"completed","input":{"path":"notes.txt"},
                "metadata":{"files":[{"file":"notes.txt","patch":"…","status":"modified","additions":2,"deletions":0}]}}""",
            )
        assertEquals(RecentStep(RecentStep.Kind.Edit, "notes.txt", RecentOutcome.Edited(2, 0), 1_000), edited)
        val created =
            step(
                "write",
                """{"status":"completed","metadata":{"files":[{"file":"new.kt","status":"added","additions":12,"deletions":0}]}}""",
            )
        assertEquals(RecentOutcome.Created(12), created?.outcome)
        val several =
            step(
                "patch",
                """{"status":"completed","metadata":{"files":[{"file":"a.kt","additions":10,"deletions":1},
                {"file":"b.kt","additions":5,"deletions":2},{"file":"c.kt","additions":3,"deletions":0}]}}""",
            )
        assertEquals("a.kt", several?.title)
        assertEquals(RecentOutcome.Files(3, 18, 3), several?.outcome)
        // An edit that didn't complete changed nothing.
        assertNull(step("edit", """{"status":"error","input":{"path":"a.kt"},"error":{"type":"permission.rejected","message":"no"}}"""))
        // No file data: the path, with no second line.
        assertEquals(
            RecentStep(RecentStep.Kind.Edit, "a.kt", RecentOutcome.Unknown, 1_000),
            step("edit", """{"status":"completed","input":{"path":"a.kt"}}"""),
        )
    }

    @Test
    fun commandsSayDoneFailedStoppedOrDenied() {
        fun outcome(state: String) = step("shell", state)?.outcome
        assertEquals(RecentOutcome.Done, outcome("""{"status":"completed","input":{"command":"date"},"metadata":{"exit":0}}"""))
        assertEquals(RecentOutcome.Failed(1), outcome("""{"status":"completed","input":{"command":"bun test"},"metadata":{"exit":1}}"""))
        assertEquals(
            RecentOutcome.Stopped,
            outcome(
                """{"status":"error","input":{"command":"sleep 40"},"error":{"type":"aborted","message":"Tool execution interrupted"}}""",
            ),
        )
        assertEquals(
            RecentOutcome.Denied,
            outcome(
                """{"status":"error","input":{"command":"rm -rf x"},"error":{"type":"aborted","message":"The user declined this tool call"}}""",
            ),
        )
        assertEquals(
            RecentOutcome.Denied,
            outcome("""{"status":"error","input":{"command":"echo m4"},"error":{"type":"permission.rejected","message":"Use printf"}}"""),
        )
        assertEquals("date", step("shell", """{"status":"completed","input":{"command":"date"}}""")?.title)
        // Any other error: the exit code when it's known, else no second line.
        assertEquals(
            RecentOutcome.Failed(124),
            outcome("""{"status":"error","input":{"command":"x"},"error":{"type":"timeout"},"metadata":{"exit":124}}"""),
        )
        assertEquals(
            RecentOutcome.Unknown,
            outcome("""{"status":"error","input":{"command":"x"},"error":{"type":"aborted","message":"gone"}}"""),
        )
        assertEquals("ls", step("bash", """{"status":"completed","input":{"command":"ls"}}""")?.title)
    }

    @Test
    fun aSubagentStartSaysItsAgent() {
        val start =
            step(
                "subagent",
                """{"status":"completed","input":{"agent":"general","description":"m4 date check","prompt":"Run date"},
                "metadata":{"sessionID":"ses_c","status":"completed"}}""",
            )
        assertEquals(RecentStep(RecentStep.Kind.Subagent, "m4 date check", RecentOutcome.Subagent("general"), 1_000), start)
    }

    @Test
    fun otherToolsAndRunningPartsAreLeftOut() {
        listOf("read", "grep", "glob", "question", "todowrite", "webfetch").forEach { name ->
            assertNull(name, step(name, """{"status":"completed","input":{"path":"a","command":"x","description":"d"}}"""))
        }
        assertNull(step("shell", """{"status":"running","input":{"command":"bun dev"}}"""))
    }

    @Test
    fun recentIsTheNewestThree() {
        val message =
            part(
                """{"id":"msg_1","type":"assistant","content":[
                ${tool("shell", """{"status":"completed","input":{"command":"one"}}""", 1)},
                ${tool("read", """{"status":"completed","input":{"path":"x"}}""", 2)},
                ${tool("shell", """{"status":"completed","input":{"command":"two"}}""", 3)},
                ${tool("shell", """{"status":"completed","input":{"command":"three"}}""", 4)},
                ${tool("shell", """{"status":"completed","input":{"command":"four"}}""", 5)}]}""",
            )
        assertEquals(listOf("four", "three", "two"), RecentSteps.from(listOf(message)).map { it.title })
    }

    @Test
    fun runningNowShowsSubagentsFirstThenLongShellsAtMostThreeRows() {
        val now = 100_000L
        val children = mapOf("c1" to ChildInfo("m4 date check", "general", 40_000), "c2" to ChildInfo("", "explore", 50_000))
        val shells = listOf(ShellRun("s1", "bun dev", 10_000), ShellRun("s2", "sleep 1", 95_000), ShellRun("s3", "tail -f log", 20_000))
        val running = RunningNow.of(listOf("c1", "c2"), children, shells, now)
        assertEquals(
            listOf(
                RunningRow.Subagent("c1", "m4 date check", "general", 40_000),
                RunningRow.Subagent("c2", "c2", "explore", 50_000),
                RunningRow.Shell("s1", "bun dev", 10_000),
            ),
            running.rows,
        )
        // The shell started 5 s ago doesn't count yet; the other long one is "+1 more".
        assertEquals(1, running.more)
        assertEquals(4, running.total)
        assertEquals(TodayState(subagentsRunning = 2, shellStarts = shells.map { it.startedAt }).running(now), running.total)
    }

    @Test
    fun sectionsComeInOrderAndEmptyOnesAreLeftOut() {
        assertEquals(
            listOf(
                DetailsSection.NeedsYou,
                DetailsSection.Waiting,
                DetailsSection.AskedOnTheSide,
                DetailsSection.Running,
                DetailsSection.Todo,
                DetailsSection.Recent,
            ),
            DetailsSections.of(pending = 1, waiting = 2, asked = 1, running = 1, todos = 4, recent = 3),
        )
        assertEquals(listOf(DetailsSection.Running, DetailsSection.Recent), DetailsSections.of(0, 0, 0, 1, 0, 2))
        assertTrue(DetailsSections.of(0, 0, 0, 0, 0, 0).isEmpty())
    }

    @Test
    fun agesAndClocks() {
        assertNull(recentAge(0, 44_000))
        assertEquals("1m", recentAge(0, 45_000))
        assertEquals("2m", recentAge(0, 120_000))
        assertEquals("3h", recentAge(0, 3 * 3_600_000L + 5))
        assertEquals("2d", recentAge(0, 2 * 86_400_000L))
        assertEquals("1:05", elapsedClock(0, 65_000))
    }
}
