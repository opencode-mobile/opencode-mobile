package dev.opencode.mobile.domain.command

import dev.opencode.mobile.domain.agent.ComposerMentionRouting

object ComposerSubmitRouting {
    fun resolve(
        text: String,
        serverCommandNames: Set<String>,
        allowsSlashCommands: Boolean = true,
    ): ComposerSubmitIntent = resolve(text, serverCommandNames.map { ServerCommand(name = it) }, allowsSlashCommands)

    /**
     * A leading `/head` that names a non-skill server command (or a v1 skill without a
     * skill id) runs on the command route. A leading v2 skill (has a skill id) stays a
     * prompt: the skill travels as an inline mention. Everything else is a prompt.
     * Slash text on an engine with [allowsSlashCommands] false is [ComposerSubmitIntent.Ignored].
     */
    fun resolve(
        text: String,
        commands: List<ServerCommand>,
        allowsSlashCommands: Boolean = true,
    ): ComposerSubmitIntent {
        val trimmed = text.trim()
        if (!allowsSlashCommands && trimmed.startsWith("/")) {
            return ComposerSubmitIntent.Ignored
        }
        val match = leadingCommand(trimmed, commands) ?: return ComposerSubmitIntent.Prompt(text = trimmed)
        if (match.isV2Skill) return ComposerSubmitIntent.Prompt(text = trimmed)
        val arguments = trimmed.split(" ", limit = 2).getOrNull(1).orEmpty()
        return ComposerSubmitIntent.ServerCommand(command = match.name, arguments = arguments)
    }

    /** The v2 skill named by a leading `/name` token, used to synthesise a span for hand-typed drafts. */
    fun leadingSkill(
        text: String,
        commands: List<ServerCommand>,
    ): ServerCommand? = leadingCommand(text.trim(), commands)?.takeIf { it.isV2Skill }

    /** Forwards to [ComposerMentionRouting.slashSuggestionQuery] with the caret at the end of [text]. */
    fun slashSuggestionQuery(text: String): String? = ComposerMentionRouting.slashSuggestionQuery(text, cursor = text.length)

    fun filterCommands(
        commands: List<ServerCommand>,
        query: String,
    ): List<ServerCommand> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return commands
        return commands.filter { command ->
            command.name.lowercase().contains(needle) ||
                command.description?.lowercase()?.contains(needle) == true
        }
    }

    private fun leadingCommand(
        trimmed: String,
        commands: List<ServerCommand>,
    ): ServerCommand? {
        if (!trimmed.startsWith("/")) return null
        val head = trimmed.split(" ", limit = 2)[0].removePrefix("/")
        if (head.isEmpty()) return null
        return commands.find { it.name == head }
    }
}
