package dev.opencode.mobile.domain.command

object ComposerSubmitRouting {
    private val slashSuggestionPattern = Regex("""^/(\S*)$""")

    fun resolve(
        text: String,
        serverCommandNames: Set<String>,
    ): ComposerSubmitIntent {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) {
            return ComposerSubmitIntent.Prompt(text = trimmed)
        }
        val parts = trimmed.split(" ", limit = 2)
        val head = parts[0].removePrefix("/")
        if (head.isEmpty() || head !in serverCommandNames) {
            return ComposerSubmitIntent.Prompt(text = trimmed)
        }
        val arguments = parts.getOrNull(1).orEmpty()
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
