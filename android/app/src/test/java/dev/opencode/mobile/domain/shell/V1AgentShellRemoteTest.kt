package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class V1AgentShellRemoteTest {
    private val messages = MutableStateFlow(listOf(message(bash(ToolStatus.Running, live = "line 1\n"))))
    private val session = AgentShellSession("ses_test", "/tmp/project", messages)
    private val remote = V1AgentShellRemote(AgentShellDomainAdapter())

    @Test
    fun shellsFollowTheBashParts() =
        runTest {
            assertEquals(
                AgentShellStatus.Running,
                remote
                    .shells(session)
                    .first()
                    .single()
                    .status,
            )

            messages.value = listOf(message(bash(ToolStatus.Completed, output = "line 1\nline 2\n", exit = 0)))

            assertEquals(
                AgentShellStatus.Exited(0),
                remote
                    .shells(session)
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun outputFollowsMetadataUntilThePartCompletes() =
        runTest {
            val shell = remote.shells(session).first().single()
            val outputs = mutableListOf<AgentShellOutput>()
            val job = launch { remote.output(shell, session).take(3).toList(outputs) }
            yield()
            messages.value = listOf(message(bash(ToolStatus.Running, live = "line 1\nline 2\n")))
            yield()
            messages.value = listOf(message(bash(ToolStatus.Completed, live = "line 1\nline 2\n", output = "line 1\nline 2\nok\n")))
            job.join()

            assertEquals(listOf("line 1\n", "line 1\nline 2\n", "line 1\nline 2\nok\n"), outputs.map { it.text })
        }

    @Test(expected = UnsupportedOperationException::class)
    fun v1CannotStopAShell() =
        runTest {
            remote.stop(remote.shells(session).first().single(), session)
        }

    private fun message(part: MessagePart.Tool) = Message(id = "msg_1", role = MessageRole.Assistant, text = "", parts = listOf(part))

    private fun bash(
        status: ToolStatus,
        live: String? = null,
        output: String? = null,
        exit: Int? = null,
    ) = MessagePart.Tool(
        toolName = "bash",
        status = status,
        id = "prt_1",
        input = buildJsonObject { put("command", "npm test") },
        output = output,
        metadata =
            buildJsonObject {
                live?.let { put("output", JsonPrimitive(it)) }
                exit?.let { put("exit", it) }
            },
        timeStartEpochMs = 1_793_548_739_990L,
    )
}
