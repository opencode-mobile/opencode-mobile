package dev.opencode.mobile.domain.agent

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
