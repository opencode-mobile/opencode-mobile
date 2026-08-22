package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.dto.AgentDto
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentDomainAdapterTest {
    private val adapter = AgentDomainAdapter()

    @Test
    fun toMentionableAgents_filtersPrimaryAndHidden() {
        val agents =
            adapter.toMentionableAgents(
                listOf(
                    AgentDto(name = "build", description = "Primary", mode = "primary", hidden = false),
                    AgentDto(name = "explore", description = "Explorer", mode = "subagent", hidden = false),
                    AgentDto(name = "title", description = null, mode = "subagent", hidden = true),
                    AgentDto(name = "reviewer", description = "  ", mode = "all", hidden = null),
                    AgentDto(name = "general", description = "General", mode = "subagent", hidden = false),
                ),
            )
        assertEquals(listOf("explore", "reviewer", "general"), agents.map { it.name })
        assertEquals("Explorer", agents[0].description)
        assertEquals(null, agents[1].description)
    }

    @Test
    fun toSelectableAgents_keepsPrimaryAndAllNotHidden() {
        val agents =
            adapter.toSelectableAgents(
                listOf(
                    AgentDto(name = "build", description = "Primary", mode = "primary", hidden = false),
                    AgentDto(name = "plan", description = "Plan", mode = "PRIMARY", hidden = false),
                    AgentDto(name = "title", description = null, mode = "primary", hidden = true),
                    AgentDto(name = "explore", description = "Explorer", mode = "subagent", hidden = false),
                    AgentDto(name = "review", description = "Reviews work", mode = "all", hidden = null),
                    AgentDto(name = "code-reviewer", description = "  ", mode = "primary", hidden = false),
                ),
            )
        assertEquals(listOf("build", "plan", "review", "code-reviewer"), agents.map { it.id })
        assertEquals(listOf("Build", "Plan", "Review", "Code Reviewer"), agents.map { it.displayName })
        assertEquals("Reviews work", agents[2].description)
        assertEquals(null, agents[3].description)
    }
}
