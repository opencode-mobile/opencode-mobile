package dev.opencode.mobile.domain.t3

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class T3ProjectRepositoryTest {
    @Test
    fun probeServerMergesProjectRootsIntoAddedWorktrees() =
        runTest {
            val store = FakeProfileStore(t3Profile)
            store.worktrees[t3Profile.baseUrl] = listOf("/home/me/custom")
            val manager = activeManager(store)
            val http = FakeT3Http()
            http.shellJson = shellJson(projects = listOf(projectJson("p1", "/repo/a"), projectJson("p2", "/home/me/custom")))
            val runtime =
                T3Runtime(
                    api = T3Api(http),
                    socket = FakeT3Socket(),
                    activeConnectionManager = manager,
                    eventStream = RecordingEventStream(),
                    scope = backgroundScope,
                )
            val repository = T3ProjectRepository(runtime, manager, store)

            repository.probeServer()
            assertEquals(listOf("/home/me/custom", "/repo/a"), store.worktrees[t3Profile.baseUrl])
            assertEquals(1, store.setWorktreeCalls)

            repository.probeServer()
            assertEquals("unchanged set is not rewritten", 1, store.setWorktreeCalls)
            assertEquals(listOf("/home/me/custom", "/repo/a"), store.worktrees[t3Profile.baseUrl])
        }
}
