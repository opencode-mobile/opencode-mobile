package dev.opencode.mobile.ui.changes

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.domain.session.VcsInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ChangesScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersChangedFilesThenPushesFocusedViewer() {
        val viewModel = ChangesViewModel(ChangesScreenRepository)
        var didExit = false
        composeTestRule.setContent {
            ChangesScreen(
                sessionId = "session",
                worktree = "/repo",
                messageId = "message",
                initialSource = ChangeSource.WorkingTree,
                onNavigateBack = { didExit = true },
                viewModel = viewModel,
            )
        }

        composeTestRule
            .onNodeWithContentDescription("3 changed files, 4 additions, 3 deletions")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Refresh").assertCountEquals(0)
        composeTestRule.onNodeWithText("Git changes").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Choose change source").performClick()
        composeTestRule.onNodeWithContentDescription("Git changes, selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Last turn changes").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Main branch").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Filter changed files").performTextInput("missing")
        composeTestRule
            .onNodeWithContentDescription("0 changed files, 0 additions, 0 deletions")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("No matching files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear filter").performClick()
        composeTestRule.onNodeWithContentDescription("Filter changed files").performTextInput("App")
        composeTestRule
            .onNodeWithContentDescription(
                "Sources/App.kt, Modified, file 1 of 1, 1 addition, 1 deletion",
            ).performClick()
        composeTestRule.onNodeWithText("Sources/App.kt").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Previous hunk").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Expand context").assertIsEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Collapse context").assertIsDisplayed()
        val firstHunkTop =
            composeTestRule
                .onNodeWithContentDescription("hunk, @@ -1,17 +1,17 @@")
                .getUnclippedBoundsInRoot()
                .top
        composeTestRule.onNodeWithContentDescription("Next hunk").assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val finalHunkTop =
                composeTestRule
                    .onNodeWithContentDescription("hunk, @@ -30 +30 @@")
                    .getUnclippedBoundsInRoot()
                    .top
            abs((finalHunkTop - firstHunkTop).value) < 2f
        }
        composeTestRule.onNodeWithContentDescription("Previous hunk").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Next hunk").assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("Filter paths").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithText("App").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("1 changed file, 1 addition, 1 deletion").assertIsDisplayed()
        composeTestRule.onNodeWithText("App").performTextClearance()
        composeTestRule
            .onNodeWithContentDescription("Sources/Removed.kt, Deleted, file 3 of 3, 1 addition, 2 deletions")
            .performClick()
        composeTestRule.onNodeWithText("Preview unavailable").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Previous hunk").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(didExit)
        assertEquals("", (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet.filter)
    }
}

private object ChangesScreenRepository : SessionRepository {
    override suspend fun getVcsInfo(directory: String?) = VcsInfo("feature/review", "main")

    override suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String?,
        context: Int,
    ) = files()

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ) = files()

    private fun files() =
        listOf(
            FileDiff("Sources/Removed.kt", null, 1, 2, FileDiffStatus.Deleted),
            file("Sources/New.kt", "old-new", 2, 0, FileDiffStatus.Added),
            FileDiff(
                "Sources/App.kt",
                expandablePatch("old-app"),
                1,
                1,
                FileDiffStatus.Modified,
            ),
        )

    private fun expandablePatch(oldLine: String): String {
        val before = (1..8).joinToString("\n") { " before $it" }
        val after = (1..8).joinToString("\n") { " after $it" }
        return "@@ -1,17 +1,17 @@\n$before\n-$oldLine\n+new\n$after\n@@ -30 +30 @@\n-old-two\n+new-two"
    }

    private fun file(
        path: String,
        oldLine: String,
        additions: Int,
        deletions: Int,
        status: FileDiffStatus,
    ) = FileDiff(path, "@@ -1,1 +1,1 @@\n-$oldLine\n+new", additions, deletions, status)

    override suspend fun listSessions(directory: String?) = emptyList<Session>()

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?) = emptyList<SessionStatus>()

    override suspend fun getSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ) = emptyList<Session>()

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ) = emptyList<SessionTodo>()

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = false

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = false

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ) = false

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ) = false
}
