package dev.opencode.mobile.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerSubmitRoutingTest {
    private val catalog = setOf("init", "review", "deploy")

    @Test
    fun resolve_matchingCommandWithArgs_returnsServerCommand() {
        val intent = ComposerSubmitRouting.resolve("/init hello world", catalog)
        assertEquals(ComposerSubmitIntent.ServerCommand(command = "init", arguments = "hello world"), intent)
    }

    @Test
    fun resolve_matchingCommandBare_returnsEmptyArguments() {
        val intent = ComposerSubmitRouting.resolve("/review", catalog)
        assertEquals(ComposerSubmitIntent.ServerCommand(command = "review", arguments = ""), intent)
    }

    @Test
    fun resolve_unknownSlash_fallsThroughToPrompt() {
        val intent = ComposerSubmitRouting.resolve("/not-a-command please", catalog)
        assertEquals(ComposerSubmitIntent.Prompt(text = "/not-a-command please"), intent)
    }

    @Test
    fun resolve_normalText_isPrompt() {
        val intent = ComposerSubmitRouting.resolve("hello", catalog)
        assertEquals(ComposerSubmitIntent.Prompt(text = "hello"), intent)
    }

    @Test
    fun slashQuery_bareSlash_returnsEmptyFilter() {
        assertEquals("", ComposerSubmitRouting.slashSuggestionQuery("/"))
    }

    @Test
    fun slashQuery_partialToken_returnsFilter() {
        assertEquals("in", ComposerSubmitRouting.slashSuggestionQuery("/in"))
    }

    @Test
    fun slashQuery_afterSpace_isClosed() {
        assertNull(ComposerSubmitRouting.slashSuggestionQuery("/init "))
        assertNull(ComposerSubmitRouting.slashSuggestionQuery("/init args"))
    }

    @Test
    fun slashQuery_nonSlash_isClosed() {
        assertNull(ComposerSubmitRouting.slashSuggestionQuery("hello"))
        assertNull(ComposerSubmitRouting.slashSuggestionQuery(""))
    }

    @Test
    fun filterCommands_matchesNameAndDescription() {
        val commands =
            listOf(
                ServerCommand(name = "init", description = "Create AGENTS.md", source = ServerCommandSource.Command),
                ServerCommand(name = "review", description = "Review changes", source = ServerCommandSource.Command),
                ServerCommand(name = "deploy", description = null, source = ServerCommandSource.Skill),
            )
        assertEquals(listOf("init"), ComposerSubmitRouting.filterCommands(commands, "ag").map { it.name })
        assertEquals(listOf("deploy"), ComposerSubmitRouting.filterCommands(commands, "dep").map { it.name })
    }

    @Test
    fun insertSelection_appendsTrailingSpace() {
        assertEquals("/init ", ComposerSubmitRouting.insertSelection("init"))
    }
}
