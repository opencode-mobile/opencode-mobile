package dev.opencode.mobile.domain.command

import dev.opencode.mobile.domain.agent.SkillMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandInvocationCollapserTest {
    @Test
    fun collapse_slashForm_formatsTokenAndArgs() {
        val commands = listOf(ServerCommand(name = "prototype", source = ServerCommandSource.Skill))
        val display = CommandInvocationCollapser.collapse("/prototype ship the home page", commands)!!
        assertEquals("prototype", display.commandName)
        assertEquals("ship the home page", display.arguments)
        assertEquals("[/prototype] ship the home page", display.text)
        assertEquals(listOf(0 until "[/prototype]".length), display.highlightRanges)
    }

    @Test
    fun collapse_expandedTemplate_withTrailingArgs() {
        val template =
            """
            # Prototype

            Throwaway code that answers a question.

            Base directory for this skill: /skills/prototype
            """.trimIndent()
        val commands =
            listOf(
                ServerCommand(
                    name = "prototype",
                    source = ServerCommandSource.Skill,
                    template = template,
                ),
            )
        val message =
            """
            $template

            me some examples of artifacts home page
            """.trimIndent()
        val display = CommandInvocationCollapser.collapse(message, commands)!!
        assertEquals("[/prototype] me some examples of artifacts home page", display.text)
        assertEquals("me some examples of artifacts home page", display.arguments)
    }

    @Test
    fun collapse_expandedTemplate_withoutArgs() {
        val template = "# Restart\n\nForce restart the server."
        val commands =
            listOf(
                ServerCommand(
                    name = "restart-opencode-server",
                    source = ServerCommandSource.Skill,
                    template = template,
                ),
            )
        val display = CommandInvocationCollapser.collapse(template, commands)!!
        assertEquals("[/restart-opencode-server]", display.text)
        assertEquals("", display.arguments)
    }

    @Test
    fun collapse_prefersLongestTemplateMatch() {
        val short = "shared prefix"
        val long = "shared prefix and more detail"
        val commands =
            listOf(
                ServerCommand(name = "short", template = short),
                ServerCommand(name = "long", template = long),
            )
        val display = CommandInvocationCollapser.collapse("$long\n\nargs", commands)!!
        assertEquals("long", display.commandName)
        assertEquals("[/long] args", display.text)
    }

    @Test
    fun collapse_unknownText_returnsNull() {
        assertNull(
            CommandInvocationCollapser.collapse(
                "hello world",
                listOf(ServerCommand(name = "prototype", template = "# Prototype")),
            ),
        )
    }

    @Test
    fun commandTokenHighlightRanges_coverEveryToken() {
        assertEquals(listOf(0..11), commandTokenHighlightRanges("[/prototype] hello"))
        assertEquals(listOf(6..17), commandTokenHighlightRanges("hello [/prototype]"))
        assertEquals(
            listOf(4..9, 26..42),
            commandTokenHighlightRanges("fix [/tdd] the login flow [/security-check]"),
        )
        assertTrue(commandTokenHighlightRanges("plain text").isEmpty())
    }

    @Test
    fun collapseSkillMentions_rewritesEverySpan() {
        val text = "fix @reviewer /tdd the login flow /security-check"
        val display =
            CommandInvocationCollapser.collapseSkillMentions(
                text,
                listOf(
                    SkillMention(id = "s2", skillId = "security-check", name = "security-check", start = 34, end = 49),
                    SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 14, end = 18),
                ),
            )!!
        assertEquals("fix @reviewer [/tdd] the login flow [/security-check]", display.text)
        assertEquals(listOf(14 until 20, 36 until 53), display.highlightRanges)
        assertEquals("tdd", display.commandName)
    }

    @Test
    fun collapseSkillMentions_fallsBackToFirstUnusedOccurrence() {
        val text = "/tdd then /tdd again"
        val display =
            CommandInvocationCollapser.collapseSkillMentions(
                text,
                listOf(
                    SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 0, end = 4),
                    SkillMention(id = "s2", skillId = "tdd", name = "tdd", start = 99, end = 103),
                    SkillMention(id = "s3", skillId = "missing", name = "missing", start = 5, end = 13),
                ),
            )!!
        assertEquals("[/tdd] then [/tdd] again", display.text)
        assertEquals(2, display.highlightRanges.size)
    }

    @Test
    fun collapseSkillMentions_nothingPlaced_returnsNull() {
        assertNull(
            CommandInvocationCollapser.collapseSkillMentions(
                "hello",
                listOf(SkillMention(id = "s1", skillId = "tdd", name = "tdd", start = 0, end = 4)),
            ),
        )
        assertNull(CommandInvocationCollapser.collapseSkillMentions("/tdd", emptyList()))
    }
}
