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
}
