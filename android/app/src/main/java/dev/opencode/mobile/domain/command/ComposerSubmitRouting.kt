package dev.opencode.mobile.domain.command

object ComposerSubmitRouting {
    private val slashSuggestionPattern = Regex("""^/(\S*)$""")

    fun resolve(
        text: String,
        serverCommandNames: Set<String>,
        allowsSlashCommands: Boolean = true,
    ): ComposerSubmitIntent = resolve(text, serverCommandNames.map { ServerCommand(name = it) }, allowsSlashCommands)

    fun resolve(
        text: String,
        commands: List<ServerCommand>,
        allowsSlashCommands: Boolean = true,
    ): ComposerSubmitIntent {
        val trimmed = text.trim()
        if (!allowsSlashCommands && trimmed.startsWith("/")) {
            return ComposerSubmitIntent.Ignored
        }
        if (!trimmed.startsWith("/")) {
            return ComposerSubmitIntent.Prompt(text = trimmed)
        }
        val parts = trimmed.split(" ", limit = 2)
        val head = parts[0].removePrefix("/")
        val match = commands.find { it.name == head }
        if (head.isEmpty() || match == null) {
            return ComposerSubmitIntent.Prompt(text = trimmed)
        }
        val arguments = parts.getOrNull(1).orEmpty()
        val skillId = match.skillId?.takeIf { it.isNotBlank() }
        if (match.source == ServerCommandSource.Skill && skillId != null) {
            return ComposerSubmitIntent.SkillPrompt(skillId = skillId, text = arguments)
        }
        return ComposerSubmitIntent.ServerCommand(command = head, arguments = arguments)
    }

    fun slashSuggestionQuery(text: String): String? {
        val match = slashSuggestionPattern.matchEntire(text) ?: return null
        return match.groupValues[1]
    }

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

    fun insertSelection(commandName: String): String = "/$commandName "
}
