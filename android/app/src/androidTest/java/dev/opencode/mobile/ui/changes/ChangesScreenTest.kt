package dev.opencode.mobile.ui.changes

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Rule
import org.junit.Test

class ChangesScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersAccessibleMultiFilePickerFilteringSelectionAndNavigation() {
        val viewModel = ChangesViewModel(ChangesScreenRepository)
        composeTestRule.setContent {
            ChangesScreen(
                sessionId = "session",
                worktree = "/repo",
                messageId = "message",
                initialSource = ChangeSource.WorkingTree,
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule
            .onNodeWithContentDescription("3 changed files, 4 additions, 3 deletions")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Refresh").assertCountEquals(0)
        composeTestRule.onNodeWithText("Working tree").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Choose change source").performClick()
        composeTestRule.onNodeWithContentDescription("Working tree, selected").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Working tree (selected)").assertCountEquals(0)
        composeTestRule.onNodeWithText("Main branch").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Main branch").assertIsDisplayed()
        val picker =
            composeTestRule.onNodeWithContentDescription(
                "Choose file. Sources/App.kt, Modified, file 1 of 3, 1 addition, 1 deletion",
            )
        picker.assertIsDisplayed().performClick()

        composeTestRule.onNodeWithText("Search changed paths").performTextInput("missing")
        composeTestRule.onNodeWithText("No matching files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear filter").performClick()

        composeTestRule
            .onAllNodesWithContentDescription(
                "Sources/New.kt, Added, file 2 of 3, 2 additions, 0 deletions",
            )[0]
            .assertIsDisplayed()
            .performClick()

        composeTestRule
            .onNodeWithContentDescription(
                "Choose file. Sources/New.kt, Added, file 2 of 3, 2 additions, 0 deletions",
            ).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Previous file").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Next file").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("2 additions, 0 deletions").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Previous file").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Next file").assertIsEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Next file").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("1 addition, 2 deletions").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(
                "Choose file. Sources/Removed.kt, Deleted, file 3 of 3, 1 addition, 2 deletions",
            ).assertIsDisplayed()
        composeTestRule
            .onAllNodesWithContentDescription(
                "Choose file. Sources/App.kt, Modified, file 1 of 3, 1 addition, 1 deletion",
            ).assertCountEquals(0)
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
            file("Sources/Removed.kt", "old-removed", 1, 2, FileDiffStatus.Deleted),
            file("Sources/New.kt", "old-new", 2, 0, FileDiffStatus.Added),
            file("Sources/App.kt", "old-app", 1, 1, FileDiffStatus.Modified),
        )

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
