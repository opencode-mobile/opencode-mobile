package dev.opencode.mobile.ui.snapshot

import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState

enum class SnapshotEvidenceRole {
    User,
    Assistant,
}

data class SnapshotEvidenceMessage(
    val id: String,
    val role: SnapshotEvidenceRole,
    val text: String,
    val metadataLabel: String? = null,
)

object SnapshotEvidenceMocks {
    const val SESSION_TITLE = "Assistant message"
    const val USER_METADATA = "Build · DeepSeek V4 Flash · 9:05 PM"

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
}
