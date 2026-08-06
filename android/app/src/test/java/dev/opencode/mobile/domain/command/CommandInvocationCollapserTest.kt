package dev.opencode.mobile.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandInvocationCollapserTest {
    @Test
    fun collapse_slashForm_formatsTokenAndArgs() {
        val commands = listOf(ServerCommand(name = "prototype", source = ServerCommandSource.Skill))
        val display = CommandInvocationCollapser.collapse("/prototype ship the home page", commands)!!
        assertEquals("prototype", display.commandName)
        assertEquals("ship the home page", display.arguments)
        assertEquals("[/prototype] ship the home page", display.text)
        assertEquals(0 until "[/prototype]".length, display.highlightRange)
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
    fun commandTokenHighlightRange_coversToken() {
        assertEquals(0..11, commandTokenHighlightRange("[/prototype] hello"))
        assertNull(commandTokenHighlightRange("hello [/prototype]"))
    }
}
