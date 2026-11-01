package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.data.remote.dto.ShellInfoDto
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShellDomainAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val adapter = AgentShellDomainAdapter(json)
    private val events = EventDomainAdapter(json)

    // v1: bash tool parts

    @Test
    fun v1RunningBashPartIsARunningShell() {
        val shell = adapter.fromBashPart(bash("prt_1", ToolStatus.Running, output = "line 1\n"), "ses_test")!!

        assertEquals("prt_1", shell.id)
        assertEquals("ses_test", shell.sessionId)
        assertEquals("npm test", shell.command)
        assertEquals("Run the tests", shell.description)
        assertEquals(AgentShellStatus.Running, shell.status)
        assertEquals(1_793_548_739_990L, shell.startedAtMs)
        assertNull(shell.finishedAtMs)
        assertFalse(shell.canStop)
    }

    @Test
    fun v1PendingBashPartCountsAsRunning() {
        assertEquals(AgentShellStatus.Running, adapter.fromBashPart(bash("prt_1", ToolStatus.Pending), "ses_test")?.status)
    }

    @Test
    fun v1OtherToolsAreNotShells() {
        val read = MessagePart.Tool(toolName = "read", status = ToolStatus.Running, id = "prt_2")
        val message =
            Message(id = "msg_1", role = MessageRole.Assistant, text = "", parts = listOf(read, bash("prt_1", ToolStatus.Running)))

        assertEquals(listOf("prt_1"), adapter.shellsFromMessages(listOf(message), "ses_test").map { it.id })
    }

    @Test
    fun v1EndStates() {
        assertEquals(
            AgentShellStatus.Exited(2),
            adapter.fromBashPart(bash("prt_1", ToolStatus.Completed, exit = 2), "ses_test")?.status,
        )
        assertEquals(
            AgentShellStatus.Exited(null),
            adapter.fromBashPart(bash("prt_1", ToolStatus.Completed), "ses_test")?.status,
        )
        assertEquals(
            AgentShellStatus.Stopped,
            adapter.fromBashPart(bash("prt_1", ToolStatus.Error, interrupted = true), "ses_test")?.status,
        )
        assertEquals(
            AgentShellStatus.Failed,
            adapter.fromBashPart(bash("prt_1", ToolStatus.Error), "ses_test")?.status,
        )
        val ended = adapter.fromBashPart(bash("prt_1", ToolStatus.Completed, exit = 0), "ses_test")!!
        assertEquals(1_793_548_742_191L, ended.finishedAtMs)
    }

    @Test
    fun v1AbortedAndTimedOutCallsEndAsCompletedWithANotice() {
        val aborted = "line 1\n\n<shell_metadata>\nUser aborted the command\n</shell_metadata>"
        val timedOut =
            "line 1\n\n<shell_metadata>\nshell tool terminated command after exceeding timeout 1000 ms.\n</shell_metadata>"

        assertEquals(
            AgentShellStatus.Stopped,
            adapter.fromBashPart(bash("prt_1", ToolStatus.Completed, finalOutput = aborted), "ses_test")?.status,
        )
        assertEquals(
            AgentShellStatus.TimedOut,
            adapter.fromBashPart(bash("prt_1", ToolStatus.Completed, finalOutput = timedOut), "ses_test")?.status,
        )
        assertEquals(
            AgentShellStatus.Exited(0),
            adapter.fromBashPart(bash("prt_1", ToolStatus.Completed, finalOutput = "User aborted the command\n", exit = 0), "ses_test")?.status,
        )
    }

    @Test
    fun v1OutputIsLiveMetadataWhileRunningThenFinalOutput() {
        assertEquals(
            AgentShellOutput("line 1\nline 2\n"),
            adapter.outputFromBashPart(bash("prt_1", ToolStatus.Running, output = "line 1\nline 2\n")),
        )
        assertEquals(
            AgentShellOutput("done\n"),
            adapter.outputFromBashPart(bash("prt_1", ToolStatus.Completed, output = "partial", finalOutput = "done\n")),
        )
        assertEquals(AgentShellOutput(""), adapter.outputFromBashPart(bash("prt_1", ToolStatus.Running)))
    }

    @Test
    fun v1OutputKeepsTheLast30000Characters() {
        val long = "a".repeat(10) + "b".repeat(AgentShellOutput.MAX_CHARS)

        val output = adapter.outputFromBashPart(bash("prt_1", ToolStatus.Running, output = long))

        assertEquals("b".repeat(AgentShellOutput.MAX_CHARS), output.text)
        assertTrue(output.isTruncated)
    }

    // v2: ShellInfo and shell.* events

    @Test
    fun v2InfoMapsToARunningStoppableShell() {
        val info = json.decodeFromString(ShellInfoDto.serializer(), AgentShellFixtures.shellInfo())

        val shell = adapter.fromInfo(info, canStop = true)

        assertEquals(AgentShellFixtures.RUNNING_ID, shell.id)
        assertEquals("ses_test", shell.sessionId)
        assertEquals(AgentShellFixtures.COMMAND, shell.command)
        assertNull(shell.description)
        assertEquals(AgentShellStatus.Running, shell.status)
        assertEquals(1_793_548_739_990L, shell.startedAtMs)
        assertTrue(shell.canStop)
    }

    @Test
    fun v2StatusMapping() {
        assertEquals(AgentShellStatus.Running, adapter.v2Status("running", null))
        assertEquals(AgentShellStatus.Exited(3), adapter.v2Status("exited", 3))
        assertEquals(AgentShellStatus.Exited(null), adapter.v2Status("exited", null))
        assertEquals(AgentShellStatus.TimedOut, adapter.v2Status("timeout", null))
        assertEquals(AgentShellStatus.Stopped, adapter.v2Status("killed", null))
    }

    @Test
    fun v2CreatedEventCarriesTheShellInfo() {
        val event = events.parseEvent(AgentShellFixtures.createdEvent())!!

        val created = adapter.shellEvent(event) as AgentShellEvent.Created

        assertEquals(AgentShellFixtures.RUNNING_ID, created.info.id)
        assertEquals("ses_test", adapter.sessionIdOf(created.info))
    }

    @Test
    fun v2ExitedEventsMapStatusExitCodeAndTime() {
        val exited = adapter.shellEvent(events.parseEvent(AgentShellFixtures.EXITED_WITH_CODE_EVENT)!!)
        val killed = adapter.shellEvent(events.parseEvent(AgentShellFixtures.KILLED_EVENT)!!)

        assertEquals(AgentShellEvent.Exited(AgentShellFixtures.EXITED_ID, AgentShellStatus.Exited(3), 1_793_548_742_191L), exited)
        assertEquals(AgentShellEvent.Exited(AgentShellFixtures.RUNNING_ID, AgentShellStatus.Stopped, 1_793_548_743_748L), killed)
    }

    @Test
    fun v2DeletedEvent() {
        assertEquals(
            AgentShellEvent.Deleted(AgentShellFixtures.RUNNING_ID),
            adapter.shellEvent(events.parseEvent(AgentShellFixtures.DELETED_EVENT)!!),
        )
    }

    private fun bash(
        id: String,
        status: ToolStatus,
        output: String? = null,
        finalOutput: String? = null,
        exit: Int? = null,
        interrupted: Boolean = false,
    ) = MessagePart.Tool(
        toolName = "bash",
        status = status,
        id = id,
        input =
            buildJsonObject {
                put("command", "npm test")
                put("description", "Run the tests")
            },
        output = finalOutput,
        metadata =
            buildJsonObject {
                output?.let { put("output", JsonPrimitive(it)) }
                exit?.let { put("exit", it) }
            },
        timeStartEpochMs = 1_793_548_739_990L,
        timeEndEpochMs = if (status == ToolStatus.Running || status == ToolStatus.Pending) null else 1_793_548_742_191L,
        isInterrupted = interrupted,
    )
}
