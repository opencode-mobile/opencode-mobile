package dev.opencode.mobile.domain.command

/**
 * Collapses server-expanded slash-command / skill templates back into a
 * compact chat display: `[/name]` plus the arguments the user typed.
 *
 * Server `POST …/command` stores the full template (and appended args) as the
 * user message text. Desktop optimistic UI shows `/name args`; after sync both
 * clients otherwise render the expanded body. Matching uses catalog templates
 * from `GET /command` (longest prefix wins). Commands that embed `$ARGUMENTS`
 * or `$N` into the template body are only collapsed when the composer text was
 * already `/name …` (no template rewrite needed).
 */
object CommandInvocationCollapser {
    data class Display(
        val commandName: String,
        val arguments: String,
        val text: String,
        /** UTF-16 code-unit range covering `[/name]` inside [text]. */
        val highlightRange: IntRange,
    )

    fun collapse(
        text: String,
        commands: List<ServerCommand>,
    ): Display? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || commands.isEmpty()) return null

        fromSlashForm(trimmed, commands.map { it.name }.toSet())?.let { return it }

        val withTemplates =
            commands
                .mapNotNull { command ->
                    val template = command.template?.trim().orEmpty()
                    if (template.isEmpty()) null else command to template
                }.sortedByDescending { it.second.length }

        for ((command, template) in withTemplates) {
            if (trimmed == template) {
                return display(command.name, arguments = "")
            }
            if (!trimmed.startsWith(template)) continue
            val remainder = trimmed.removePrefix(template)
            // Server appends user args after the template with a blank line when
            // the template has no $ARGUMENTS / $N placeholders.
            val arguments =
                when {
                    remainder.startsWith("\n\n") -> remainder.drop(2).trimStart()
                    remainder.startsWith("\n") -> remainder.drop(1).trimStart()
                    remainder.startsWith(" ") -> remainder.trimStart()
                    else -> continue
                }
            return display(command.name, arguments)
        }
        return null
    }

    private fun fromSlashForm(
        trimmed: String,
        commandNames: Set<String>,
    ): Display? {
        if (!trimmed.startsWith("/")) return null
        val parts = trimmed.split(" ", limit = 2)
        val name = parts[0].removePrefix("/")
        if (name.isEmpty() || name !in commandNames) return null
        val arguments = parts.getOrNull(1).orEmpty().trim()
        return display(name, arguments)
    }

    private fun display(
        commandName: String,
        arguments: String,
    ): Display {
        val token = "[/$commandName]"
        val text =
            if (arguments.isEmpty()) {
                token
            } else {
                "$token $arguments"
            }
        return Display(
            commandName = commandName,
            arguments = arguments,
            text = text,
            highlightRange = 0 until token.length,
        )
    }
}
