package dev.opencode.mobile.ui.snapshot

import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.SessionListWorktreeEmptyCopy

enum class SnapshotEvidenceRole {
    User,
    Assistant,
    ChildSession,
}

data class SnapshotEvidenceChildSession(
    val agentName: String,
    val letter: String,
    val isBackground: Boolean,
    val secondLine: String?,
    /** Elapsed offset from now, so a snapshot captures a stable `m:ss`. */
    val elapsedMs: Long?,
    val showsChevron: Boolean,
) {
    val timeStartEpochMs: Long?
        get() = elapsedMs?.let { System.currentTimeMillis() - it }
}

data class SnapshotEvidenceMessage(
    val id: String,
    val role: SnapshotEvidenceRole,
    val text: String,
    val metadataLabel: String? = null,
    val childSession: SnapshotEvidenceChildSession? = null,
)

object SnapshotEvidenceMocks {
    const val SESSION_TITLE = "Assistant message"
    const val USER_METADATA = "Build · DeepSeek V4 Flash · 9:05 PM"
    const val CHILD_SESSION_TITLE = "Find auth bugs"

    /** Home filtered to a worktree with no sessions while sibling worktrees have some. */
    const val WORKTREE_EMPTY_MESSAGE = SessionListWorktreeEmptyCopy.OTHER_WORKTREES

    val userPrompt =
        SnapshotEvidenceMessage(
            id = "snapshot-user-1",
            role = SnapshotEvidenceRole.User,
            text = "Walk me through the assistant message on mobile.",
            metadataLabel = USER_METADATA,
        )

    val assistantReply =
        SnapshotEvidenceMessage(
            id = "snapshot-assistant-1",
            role = SnapshotEvidenceRole.Assistant,
            text =
                """
                The timeline should alternate outgoing prompts and incoming replies with comfortable spacing.
                When the latest messages are off-screen, a floating scroll-to-end pill appears above the composer.
                """.trimIndent(),
        )

    val conversation = listOf(userPrompt, assistantReply)

    val childSessionUserPrompt =
        SnapshotEvidenceMessage(
            id = "snapshot-child-user-1",
            role = SnapshotEvidenceRole.User,
            text = "Find auth bugs in the login flow.",
            metadataLabel = USER_METADATA,
        )

    val runningChildSession =
        SnapshotEvidenceChildSession(
            agentName = "explore",
            letter = "E",
            isBackground = true,
            secondLine = "Find auth bugs",
            elapsedMs = 84_000,
            showsChevron = true,
        )

    val runningChildSessionMessage =
        SnapshotEvidenceMessage(
            id = "snapshot-child-1",
            role = SnapshotEvidenceRole.ChildSession,
            text = "",
            childSession = runningChildSession,
        )

    val childSessionConversation =
        listOf(childSessionUserPrompt, runningChildSessionMessage)

    val idleComposer =
        OpenCodeSessionComposerState.Prompt(
            text = "",
            selectedModelLabel = "Claude Sonnet 4",
            selectedModeLabel = "Build",
            selectedVariantLabel = "Default",
            showVariant = true,
            sendEnabled = false,
            showStop = false,
            stopEnabled = false,
        )

    val childLockoutAssistantReply =
        SnapshotEvidenceMessage(
            id = "snapshot-child-assistant-1",
            role = SnapshotEvidenceRole.Assistant,
            text = "Login sets a session cookie without SameSite. CSRF on POST /login is missing.",
        )

    val childLockoutConversation =
        listOf(childSessionUserPrompt, childLockoutAssistantReply)

    val childLockoutComposer =
        OpenCodeSessionComposerState.ChildLockout(
            selectedModelLabel = "Claude Sonnet 4",
            selectedVariantLabel = "High",
            showVariant = true,
        )
}
