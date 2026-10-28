package dev.opencode.mobile.domain.command

import dev.opencode.mobile.domain.agent.SkillMention

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
        /** UTF-16 code-unit ranges covering each `[/name]` inside [text]. */
        val highlightRanges: List<IntRange>,
    )

    /**
     * Rewrites every placeable skill span of [text] from `/name` to `[/name]`. A span whose
     * offsets no longer match the text falls back to the first unused occurrence of its
     * token; one that cannot be placed is skipped. Null when nothing was placed.
     */
    fun collapseSkillMentions(
        text: String,
        skillMentions: List<SkillMention>,
    ): Display? {
        if (skillMentions.isEmpty() || text.isEmpty()) return null
        val placed = mutableListOf<IntRange>()

        fun free(range: IntRange): Boolean = placed.none { it.first < range.last + 1 && it.last + 1 > range.first }

        skillMentions.sortedBy { it.start }.forEach { mention ->
            val token = mention.token
            val exact = mention.start until mention.end
            val matches =
                mention.start >= 0 &&
                    mention.end <= text.length &&
                    mention.start < mention.end &&
                    text.substring(mention.start, mention.end) == token
            if (matches && free(exact)) {
                placed += exact
                return@forEach
            }
            var from = 0
            while (from <= text.length) {
                val index = text.indexOf(token, from)
                if (index < 0) break
                val candidate = index until index + token.length
                if (free(candidate)) {
                    placed += candidate
                    break
                }
                from = index + 1
            }
        }
        if (placed.isEmpty()) return null
        val ordered = placed.sortedBy { it.first }
        val out = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        ordered.forEach { range ->
            out.append(text, cursor, range.first)
            val start = out.length
            out.append('[').append(text, range.first, range.last + 1).append(']')
            ranges += start until out.length
            cursor = range.last + 1
        }
        out.append(text, cursor, text.length)
        val first = ordered.first()
        return Display(
            commandName = text.substring(first.first + 1, first.last + 1),
            arguments = "",
            text = out.toString(),
            highlightRanges = ranges,
        )
    }

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
            highlightRanges = listOf(0 until token.length),
        )
    }
}
