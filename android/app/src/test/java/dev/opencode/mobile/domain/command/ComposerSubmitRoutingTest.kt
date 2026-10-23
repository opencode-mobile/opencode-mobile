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
    fun resolve_skillWithId_sendsSkillPromptNotCommand() {
        val intent =
            ComposerSubmitRouting.resolve(
                "/OpenCode check the error",
                listOf(
                    ServerCommand(name = "init", source = ServerCommandSource.Command),
                    ServerCommand(
                        name = "OpenCode",
                        source = ServerCommandSource.Skill,
                        skillId = "opencode",
                    ),
                ),
            )
        assertEquals(ComposerSubmitIntent.Prompt(text = "/OpenCode check the error"), intent)
    }

    @Test
    fun leadingSkill_matchesOnlyV2SkillsAtOffsetZero() {
        val commands =
            listOf(
                ServerCommand(name = "init", source = ServerCommandSource.Command),
                ServerCommand(name = "OpenCode", source = ServerCommandSource.Skill, skillId = "opencode"),
                ServerCommand(name = "deploy", source = ServerCommandSource.Skill),
            )
        assertEquals("opencode", ComposerSubmitRouting.leadingSkill("/OpenCode args", commands)?.skillId)
        assertNull(ComposerSubmitRouting.leadingSkill("/init args", commands))
        assertNull(ComposerSubmitRouting.leadingSkill("/deploy now", commands))
        assertNull(ComposerSubmitRouting.leadingSkill("fix /OpenCode", commands))
    }

    @Test
    fun resolve_skillWithoutId_staysOnCommandRoute() {
        val intent =
            ComposerSubmitRouting.resolve(
                "/deploy now",
                listOf(ServerCommand(name = "deploy", source = ServerCommandSource.Skill)),
            )
        assertEquals(ComposerSubmitIntent.ServerCommand(command = "deploy", arguments = "now"), intent)
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
}
