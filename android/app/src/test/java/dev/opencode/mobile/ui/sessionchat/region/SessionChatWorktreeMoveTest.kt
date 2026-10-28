package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.session.SessionError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatWorktreeMoveTest {
    private val project =
        HomeProject(
            key = "proj",
            projectId = "proj",
            displayName = "repo",
            memberPaths = listOf("/repo"),
            isRepository = true,
            worktrees =
                listOf(
                    HomeWorktree("/repo", branch = "dev", isMain = true),
                    HomeWorktree("/repo-feature", branch = "feature/x"),
                    HomeWorktree("/repo-detached"),
                ),
        )

    @Test
    fun availabilityMatrixChecksV2ThenSettingThenSessionThenWorktrees() {
        fun resolve(
            v2: Boolean = true,
            on: Boolean = true,
            created: Boolean = true,
            count: Int = 2,
            canCreate: Boolean = false,
        ) = SessionChatWorktreeMoveAvailability.resolve(v2, on, created, count, canCreate)

        assertEquals(
            SessionChatWorktreeMoveAvailability(false, "Needs an opencode v2 server"),
            resolve(v2 = false, on = false, count = 1, canCreate = true),
        )
        assertEquals(
            SessionChatWorktreeMoveAvailability(false, "Turn on Worktrees in Settings"),
            resolve(on = false, count = 1, canCreate = true),
        )
        assertEquals(SessionChatWorktreeMoveAvailability(false, null), resolve(created = false, count = 1, canCreate = true))
        assertEquals(SessionChatWorktreeMoveAvailability(false, "This project has no other worktrees"), resolve(count = 1))
        assertEquals(SessionChatWorktreeMoveAvailability(true, null), resolve())
    }

    @Test
    fun oneCheckoutStillOpensTheSheetWhileANewWorktreeCanBeCreated() {
        assertEquals(
            SessionChatWorktreeMoveAvailability(true, null),
            SessionChatWorktreeMoveAvailability.resolve(true, true, true, worktreeCount = 1, canCreateWorktree = true),
        )
        assertEquals(
            SessionChatWorktreeMoveAvailability(false, "This project has no other worktrees"),
            SessionChatWorktreeMoveAvailability.resolve(true, true, true, worktreeCount = 1, canCreateWorktree = false),
        )
        assertEquals(
            SessionChatWorktreeMoveAvailability(true, null),
            SessionChatWorktreeMoveAvailability.resolve(true, true, true, worktreeCount = 3, canCreateWorktree = false),
        )
    }

    @Test
    fun aCreatedCheckoutRowIsTitledAsGivenWithItsFolder() {
        val row = SessionChatWorktreeMoveRow.created("/wt/ab12cd/brave-otter", "brave-otter")

        assertEquals(SessionChatWorktreeMoveRow("/wt/ab12cd/brave-otter", "brave-otter", "brave-otter", isCurrent = false), row)
    }

    @Test
    fun rowsListTheProjectsWorktreesInHomeOrderWithTheCurrentOneChecked() {
        val rows = SessionChatWorktreeMoveRow.rows(project, "/repo-feature/")

        assertEquals(listOf("dev", "feature/x", "repo-detached"), rows.map { it.title })
        assertEquals(listOf("main checkout", "repo-feature", "repo-detached"), rows.map { it.subtitle })
        assertEquals(listOf(false, true, false), rows.map { it.isCurrent })
        assertEquals(emptyList<SessionChatWorktreeMoveRow>(), SessionChatWorktreeMoveRow.rows(null, "/repo"))
    }

    @Test
    fun idleMoveClosesTheSheetWithoutANotice() =
        runTest {
            val calls = mutableListOf<Pair<String, String>>()
            val move = region(this) { id, directory -> calls += id to directory }
            move.openSheet()

            move.move("ses-1", row("/repo-feature", "feature/x"), isBusy = false)
            advanceUntilIdle()

            assertEquals(listOf("ses-1" to "/repo-feature"), calls)
            assertFalse(move.state.value.sheetVisible)
            assertNull(move.state.value.notice)
            assertNull(move.state.value.submittingDirectory)
        }

    @Test
    fun busyMoveSaysItWaitsForTheTurn() =
        runTest {
            val move = region(this) { _, _ -> }

            move.move("ses-1", row("/repo-feature", "feature/x"), isBusy = true)
            advanceUntilIdle()

            assertEquals("Moves to feature/x after this turn.", move.state.value.notice)
        }

    @Test
    fun failureSurfacesTheServerMessage() =
        runTest {
            val move =
                region(this) { _, _ ->
                    throw SessionError.Http(
                        400,
                        "HTTP 400",
                        body = """{"_tag":"LocationUnavailableError","message":"Destination is not a directory"}""",
                    )
                }
            move.openSheet()

            move.move("ses-1", row("/repo-feature", "feature/x"), isBusy = false)
            advanceUntilIdle()

            assertEquals("Couldn't move the session: Destination is not a directory", move.state.value.errorMessage)
            assertFalse(move.state.value.sheetVisible)
            move.clearMessages()
            assertNull(move.state.value.errorMessage)
        }

    @Test
    fun theCurrentWorktreeIsNotAMove() =
        runTest {
            val calls = mutableListOf<String>()
            val move = region(this) { _, directory -> calls += directory }

            move.move("ses-1", row("/repo", "dev", isCurrent = true), isBusy = false)
            advanceUntilIdle()

            assertTrue(calls.isEmpty())
        }

    @Test
    fun sessionMovedForTheOpenSessionReportsItsNewDirectory() =
        runTest {
            val move = region(this) { _, _ -> }
            move.openSheet()

            assertNull(move.onSessionMoved(SessionMovedUpdate("other", "/repo-feature"), boundSessionId = "ses-1", boundDirectory = "/repo"))
            assertNull(move.onSessionMoved(SessionMovedUpdate("ses-1", "/repo/"), boundSessionId = "ses-1", boundDirectory = "/repo"))
            assertTrue(move.state.value.sheetVisible)

            assertEquals(
                "/repo-feature",
                move.onSessionMoved(SessionMovedUpdate("ses-1", "/repo-feature"), boundSessionId = "ses-1", boundDirectory = "/repo"),
            )
            assertFalse(move.state.value.sheetVisible)
        }

    @Test
    fun sessionMovedClearsTheAfterThisTurnNotice() =
        runTest {
            val move = region(this) { _, _ -> }
            move.move("ses-1", row("/repo-feature", "feature/x", isCurrent = false), isBusy = true)
            advanceUntilIdle()
            assertEquals(SessionChatWorktreeMoveCopy.movesAfterTurn("feature/x"), move.state.value.notice)

            move.onSessionMoved(SessionMovedUpdate("ses-1", "/repo-feature"), boundSessionId = "ses-1", boundDirectory = "/repo")

            assertNull(move.state.value.notice)
        }

    private fun row(
        directory: String,
        title: String,
        isCurrent: Boolean = false,
    ) = SessionChatWorktreeMoveRow(directory = directory, title = title, subtitle = "", isCurrent = isCurrent)

    private fun region(
        scope: TestScope,
        moveSession: suspend (String, String) -> Unit,
    ) = SessionChatWorktreeMove(moveSession = moveSession, scope = scope).apply {
        ioDispatcher = StandardTestDispatcher(scope.testScheduler)
    }
}
