package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.domain.shell.AgentShellOutput
import dev.opencode.mobile.domain.shell.AgentShellRepository
import dev.opencode.mobile.domain.shell.AgentShellSession
import dev.opencode.mobile.domain.shell.AgentShellStatus
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShells
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShellsCopy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatShellsTest {
    private val repository = FakeShellRepository()

    @Test
    fun listsRunningShellsOfTheBoundSession() =
        runTest {
            val region = region()
            repository.shells.value = listOf(shell("sh_1"), shell("sh_2").copy(status = AgentShellStatus.Exited(0)))
            runCurrent()

            assertEquals(listOf("sh_1"), region.state.value.shells.map { it.id })
            assertEquals(AgentShellSession("ses_test", "/tmp/project", emptyFlow()).sessionId, repository.boundSession?.sessionId)
            assertEquals("/tmp/project", repository.boundSession?.directory)
        }

    @Test
    fun outputIsReadOnlyWhileTheSheetIsObserved() =
        runTest {
            val region = region()
            repository.shells.value = listOf(shell("sh_1"))
            runCurrent()

            region.open("sh_1")
            runCurrent()
            assertEquals(0, repository.activeOutputs)

            region.setOutputObserved(true)
            runCurrent()
            assertEquals(1, repository.activeOutputs)
            repository.output.value = AgentShellOutput("line 1\n")
            runCurrent()
            assertEquals("line 1\n", region.state.value.output?.output?.text)

            // Backgrounded: polling stops, the text stays.
            region.setOutputObserved(false)
            runCurrent()
            assertEquals(0, repository.activeOutputs)
            assertEquals("line 1\n", region.state.value.output?.output?.text)

            region.setOutputObserved(true)
            runCurrent()
            region.close()
            runCurrent()
            assertEquals(0, repository.activeOutputs)
            assertNull(region.state.value.output)
        }

    @Test
    fun anEndedShellLeavesTheListButItsSheetShowsTheEnd() =
        runTest {
            val region = region()
            repository.shells.value = listOf(shell("sh_1"))
            runCurrent()
            region.open("sh_1")
            region.setOutputObserved(true)
            runCurrent()

            repository.shells.value = listOf(shell("sh_1").copy(status = AgentShellStatus.Exited(1), finishedAtMs = 2_000, canStop = false))
            runCurrent()

            assertEquals(emptyList<AgentShell>(), region.state.value.shells)
            val sheet = region.state.value.output!!
            assertEquals(AgentShellStatus.Exited(1), sheet.shell.status)
            assertFalse(sheet.showsStop)
            // The ended shell is read once more for its last output.
            assertEquals(listOf(true, false), repository.outputRunningFlags)

            repository.shells.value = emptyList()
            runCurrent()
            assertEquals(AgentShellStatus.Exited(1), region.state.value.output?.shell?.status)
        }

    @Test
    fun stopShowsStoppingUntilTheShellEnds() =
        runTest {
            val region = region()
            repository.shells.value = listOf(shell("sh_1"))
            runCurrent()
            region.open("sh_1")
            assertTrue(region.state.value.output!!.showsStop)

            region.stop("sh_1")
            runCurrent()
            assertTrue(region.state.value.output!!.isStopping)
            assertEquals(listOf("sh_1"), repository.stopped)

            repository.stopResult.complete(Unit)
            runCurrent()
            assertTrue(region.state.value.output!!.isStopping)

            repository.shells.value = listOf(shell("sh_1").copy(status = AgentShellStatus.Stopped, canStop = false))
            runCurrent()
            val sheet = region.state.value.output!!
            assertFalse(sheet.isStopping)
            assertEquals(AgentShellStatus.Stopped, sheet.shell.status)
            assertFalse(sheet.showsStop)
        }

    @Test
    fun stopFailureShowsTheErrorAndEnablesStopAgain() =
        runTest {
            val region = region()
            repository.shells.value = listOf(shell("sh_1"))
            runCurrent()
            region.open("sh_1")

            region.stop("sh_1")
            repository.stopResult.completeExceptionally(IllegalStateException("HTTP 500"))
            runCurrent()

            val sheet = region.state.value.output!!
            assertFalse(sheet.isStopping)
            assertEquals(SessionChatShellsCopy.STOP_FAILED, sheet.stopError)
            assertTrue(sheet.showsStop)
        }

    @Test
    fun stopIsNotOfferedForShellsThatCannotStop() =
        runTest {
            val region = region()
            repository.shells.value = listOf(shell("prt_1").copy(canStop = false))
            runCurrent()
            region.open("prt_1")

            assertFalse(region.state.value.output!!.showsStop)
            region.stop("prt_1")
            runCurrent()
            assertEquals(emptyList<String>(), repository.stopped)
        }

    @Test
    fun aShellRemovedWhileStoppingReadsAsStopped() =
        runTest {
            val region = region(nowMs = 5_000)
            repository.shells.value = listOf(shell("sh_1"))
            runCurrent()
            region.open("sh_1")
            region.stop("sh_1")
            repository.stopResult.complete(Unit)
            runCurrent()

            repository.shells.value = emptyList()
            runCurrent()

            val sheet = region.state.value.output!!
            assertEquals(AgentShellStatus.Stopped, sheet.shell.status)
            assertEquals(5_000L, sheet.shell.finishedAtMs)
            assertFalse(sheet.isStopping)
        }

    private fun TestScope.region(nowMs: Long = 0): SessionChatShells =
        SessionChatShells(repository, backgroundScope, nowMs = { nowMs }).also {
            it.bind("ses_test", "/tmp/project", emptyFlow())
            runCurrent()
        }

    private fun shell(id: String) =
        AgentShell(
            id = id,
            sessionId = "ses_test",
            command = "npm test",
            description = null,
            status = AgentShellStatus.Running,
            startedAtMs = 1_000,
            finishedAtMs = null,
            canStop = true,
        )

    private class FakeShellRepository : AgentShellRepository {
        val shells = MutableStateFlow<List<AgentShell>>(emptyList())
        val output = MutableStateFlow<AgentShellOutput?>(null)
        var boundSession: AgentShellSession? = null
        var activeOutputs = 0
        val outputRunningFlags = mutableListOf<Boolean>()
        val stopped = mutableListOf<String>()
        val stopResult = CompletableDeferred<Unit>()

        override fun shells(session: AgentShellSession): Flow<List<AgentShell>> {
            boundSession = session
            return shells
        }

        override fun output(
            shell: AgentShell,
            session: AgentShellSession,
        ): Flow<AgentShellOutput> {
            outputRunningFlags += shell.isRunning
            return kotlinx.coroutines.flow.flow {
                output.collect { value -> if (value != null) emit(value) }
            }.onStart { activeOutputs++ }
                .onCompletion { activeOutputs-- }
        }

        override suspend fun stop(
            shell: AgentShell,
            session: AgentShellSession,
        ) {
            stopped += shell.id
            stopResult.await()
        }
    }
}
