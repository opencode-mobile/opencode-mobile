package dev.opencode.mobile.domain.command

private val commandTokenPattern = Regex("""\[/[^\]\s]+\]""")

/**
 * UTF-16 ranges covering every `[/token]` occurrence in collapsed slash-command /
 * skill chat text; empty when the text carries none.
 */
fun commandTokenHighlightRanges(text: String): List<IntRange> = commandTokenPattern.findAll(text).map { it.range }.toList()
