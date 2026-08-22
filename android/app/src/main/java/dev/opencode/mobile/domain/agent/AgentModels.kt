package dev.opencode.mobile.domain.agent

import java.util.Locale

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

        fun displayNameFor(id: String): String =
            id
                .split('-', '_')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                    }
                }.ifEmpty { id }
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

data class AgentPartPayload(
    val name: String,
    val sourceValue: String,
    val start: Int,
    val end: Int,
)

data class MentionInsertResult(
    val text: String,
    val cursor: Int,
    val mentions: List<AgentMention>,
)

data class MentionReconcileResult(
    val text: String,
    val cursor: Int,
    val mentions: List<AgentMention>,
)

sealed class ComposerSuggestionMode {
    data object None : ComposerSuggestionMode()

    data class Slash(
        val query: String,
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
