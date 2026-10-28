package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.domain.text.TitleCaseIdentifier

data class SelectableAgent(
    val id: String,
    val displayName: String,
    val description: String? = null,
) {
    companion object {
        const val DefaultId = "build"

        val Fallback =
            listOf(
                SelectableAgent(id = "build", displayName = "Build"),
                SelectableAgent(id = "plan", displayName = "Plan"),
            )

        fun displayNameFor(id: String): String = TitleCaseIdentifier.displayName(id)
    }
}

data class MentionableAgent(
    val name: String,
    val description: String? = null,
)

data class AgentMention(
    val id: String,
    val name: String,
    val start: Int,
    val end: Int,
) {
    val token: String get() = "@$name"
}

/** A project file referenced from the composer. Separate from an agent mention and from an Attachment. */
data class FileMention(
    val id: String,
    val path: String,
    val start: Int,
    val end: Int,
) {
    val token: String get() = "@$path"
}

/** A v2 skill admitted from the composer as an inline `/name` span. */
data class SkillMention(
    val id: String,
    val skillId: String,
    val name: String,
    val start: Int,
    val end: Int,
) {
    val token: String get() = "/$name"
}

data class AgentPartPayload(
    val name: String,
    val sourceValue: String,
    val start: Int,
    val end: Int,
)

data class SkillPartPayload(
    val skillId: String,
    val name: String,
    val sourceValue: String,
    val start: Int,
    val end: Int,
)

data class FilePartPayload(
    val path: String,
    val filename: String,
    val sourceValue: String,
    val start: Int,
    val end: Int,
)

data class MentionInsertResult(
    val text: String,
    val cursor: Int,
    val mentions: List<AgentMention>,
    val fileMentions: List<FileMention> = emptyList(),
    val skillMentions: List<SkillMention> = emptyList(),
)

data class MentionReconcileResult(
    val text: String,
    val cursor: Int,
    val mentions: List<AgentMention>,
    val fileMentions: List<FileMention> = emptyList(),
    val skillMentions: List<SkillMention> = emptyList(),
)

data class TrimmedPrompt(
    val text: String,
    val agentMentions: List<AgentMention>,
    val fileMentions: List<FileMention> = emptyList(),
    val skillMentions: List<SkillMention> = emptyList(),
)

sealed class ComposerSuggestionMode {
    data object None : ComposerSuggestionMode()

    /** [isLeading]: the open `/` sits at offset 0, so server commands are offered too. */
    data class Slash(
        val query: String,
        val isLeading: Boolean = true,
    ) : ComposerSuggestionMode()

    data class At(
        val query: String,
    ) : ComposerSuggestionMode()
}

sealed class AgentError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : AgentError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : AgentError()

    data class Decoding(
        override val cause: Throwable,
    ) : AgentError()
}
