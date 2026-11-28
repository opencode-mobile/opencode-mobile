package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2SessionSelectionTest {
    private val gpt5 = V2ModelRefDto(id = "gpt-5", providerId = "openai")

    @Test
    fun newSessionWithoutAgentOrModel_postsBoth() {
        val switches = v2SelectionSwitches(session(agent = null, model = null), "build", switch(gpt5))

        assertEquals("build", switches.agent)
        assertEquals(switch(gpt5), switches.model)
    }

    @Test
    fun unchangedAgentAndModel_postsNothing() {
        val switches = v2SelectionSwitches(session(agent = "build", model = gpt5), "build", switch(gpt5))

        assertNull(switches.agent)
        assertNull(switches.model)
    }

    @Test
    fun changedAgent_postsAgentOnly() {
        val switches = v2SelectionSwitches(session(agent = "build", model = gpt5), "plan", switch(gpt5))

        assertEquals("plan", switches.agent)
        assertNull(switches.model)
    }

    @Test
    fun switchingBackToEarlierAgent_postsIt() {
        val switches = v2SelectionSwitches(session(agent = "plan", model = gpt5), "build", null)

        assertEquals("build", switches.agent)
    }

    @Test
    fun changedVariant_postsModel() {
        val high = gpt5.copy(variant = "high")

        assertEquals(switch(high), v2SelectionSwitches(session(agent = null, model = gpt5), null, switch(high)).model)
        assertEquals(switch(gpt5), v2SelectionSwitches(session(agent = null, model = high), null, switch(gpt5)).model)
    }

    @Test
    fun defaultVariantMatchesNoVariant_likeTheServer() {
        val switches = v2SelectionSwitches(session(agent = null, model = gpt5.copy(variant = "default")), null, switch(gpt5))

        assertNull(switches.model)
    }

    @Test
    fun otherProviderOrModel_postsModel() {
        val current = session(agent = null, model = gpt5)

        assertEquals(
            switch(gpt5.copy(providerId = "azure")),
            v2SelectionSwitches(current, null, switch(gpt5.copy(providerId = "azure"))).model,
        )
        assertEquals(
            switch(gpt5.copy(id = "gpt-5-mini")),
            v2SelectionSwitches(current, null, switch(gpt5.copy(id = "gpt-5-mini"))).model,
        )
    }

    @Test
    fun unknownSession_postsEveryPick() {
        val switches = v2SelectionSwitches(null, "build", switch(gpt5))

        assertEquals("build", switches.agent)
        assertEquals(switch(gpt5), switches.model)
    }

    @Test
    fun blankOrMissingPicks_postNothing() {
        val switches = v2SelectionSwitches(null, " ", null)

        assertNull(switches.agent)
        assertNull(switches.model)
    }

    private fun session(
        agent: String?,
        model: V2ModelRefDto?,
    ) = V2SessionDto(id = "ses_1", agent = agent, model = model)

    private fun switch(model: V2ModelRefDto) = V2SwitchModelRequestDto(model)
}
