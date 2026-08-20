package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.QuestionInfoDto
import dev.opencode.mobile.data.remote.dto.QuestionOptionDto
import dev.opencode.mobile.data.remote.dto.QuestionRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionToolDto
import dev.opencode.mobile.data.remote.dto.V2FormFieldDto
import dev.opencode.mobile.data.remote.dto.V2FormFieldOptionDto
import dev.opencode.mobile.data.remote.dto.V2FormInfoDto
import dev.opencode.mobile.data.remote.dto.V2FormMetadataDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionRequestMappingTest {
    private val adapter = SessionDomainAdapter()

    @Test
    fun `maps question request and defaults custom to true`() {
        val request =
            adapter.toQuestionRequest(
                QuestionRequestDto(
                    id = "que_1",
                    sessionId = "ses_1",
                    questions =
                        listOf(
                            QuestionInfoDto(
                                question = "Which approach?",
                                header = "Approach",
                                options =
                                    listOf(
                                        QuestionOptionDto(label = "A", description = "First"),
                                        QuestionOptionDto(label = "B", description = "Second"),
                                    ),
                                multiple = true,
                                custom = null,
                            ),
                        ),
                    tool = QuestionToolDto(messageId = "msg_1", callId = "call_1"),
                ),
            )

        assertEquals("que_1", request.id)
        assertEquals("ses_1", request.sessionId)
        assertEquals("msg_1", request.toolMessageId)
        assertEquals("call_1", request.toolCallId)
        assertEquals(1, request.questions.size)
        assertTrue(request.questions[0].multiple)
        assertTrue(request.questions[0].custom)
        assertEquals("A", request.questions[0].options[0].label)
    }

    @Test
    fun `custom false is preserved`() {
        val info =
            adapter.toQuestionInfo(
                QuestionInfoDto(
                    question = "Pick one",
                    header = "Pick",
                    options = emptyList(),
                    custom = false,
                ),
            )
        assertFalse(info.custom)
    }

    @Test
    fun `maps published tool id when callID is absent`() {
        val request =
            adapter.toQuestionRequest(
                QuestionRequestDto(
                    id = "que_1",
                    sessionId = "ses_1",
                    tool = QuestionToolDto(messageId = "msg_1", id = "call_1"),
                ),
            )
        assertEquals("call_1", request.toolCallId)
        assertEquals("msg_1", request.toolMessageId)
    }

    @Test
    fun `maps published form request onto question dock`() {
        val request =
            adapter.toQuestionRequest(
                V2FormInfoDto(
                    id = "frm_1",
                    sessionId = "ses_1",
                    title = "Questions",
                    metadata =
                        V2FormMetadataDto(
                            kind = "question",
                            tool = QuestionToolDto(messageId = "msg_1", id = "call_1"),
                        ),
                    fields =
                        listOf(
                            V2FormFieldDto(
                                key = "q0",
                                title = "SSE2 goal",
                                description = "What would you like to do with SSE2?",
                                options =
                                    listOf(
                                        V2FormFieldOptionDto(
                                            value = "Check CPU support",
                                            label = "Check CPU support",
                                            description = "Verify SSE2",
                                        ),
                                    ),
                                custom = true,
                            ),
                        ),
                ),
            )

        assertEquals("frm_1", request.id)
        assertEquals("ses_1", request.sessionId)
        assertEquals("msg_1", request.toolMessageId)
        assertEquals("call_1", request.toolCallId)
        assertEquals("What would you like to do with SSE2?", request.questions.single().question)
        assertEquals("SSE2 goal", request.questions.single().header)
        assertEquals("q0", request.questions.single().fieldKey)
        assertEquals("Check CPU support", request.questions.single().options.single().label)
        assertEquals("Check CPU support", request.questions.single().options.single().value)
        assertEquals(
            "Wait for instructions",
            adapter.formAnswer(listOf(listOf("Wait for instructions"))).getValue("q0").jsonPrimitive.content,
        )
    }

    @Test
    fun `form reply uses field keys and option values`() {
        val request =
            adapter.toQuestionRequest(
                V2FormInfoDto(
                    id = "frm_1",
                    sessionId = "ses_1",
                    title = "Web Search",
                    fields =
                        listOf(
                            V2FormFieldDto(
                                key = "choice",
                                title = "Web Search",
                                description = "Allow OpenCode to search the web for up-to-date information?",
                                options =
                                    listOf(
                                        V2FormFieldOptionDto(
                                            value = "allow",
                                            label = "Allow search via Exa, Firecrawl, Parallel, Tavily",
                                        ),
                                        V2FormFieldOptionDto(value = "choose", label = "Choose another provider"),
                                        V2FormFieldOptionDto(value = "disable", label = "Disable web search"),
                                    ),
                                custom = false,
                            ),
                        ),
                ),
            )

        val question = request.questions.single()
        assertEquals("choice", question.fieldKey)
        assertEquals("allow", question.options.first().value)
        assertEquals(
            "allow",
            adapter.formAnswer(
                request.questions,
                listOf(listOf("Allow search via Exa, Firecrawl, Parallel, Tavily")),
            ).getValue("choice").jsonPrimitive.content,
        )
    }

    @Test
    fun `form reply uses arrays for multiselect fields`() {
        val request =
            adapter.toQuestionRequest(
                V2FormInfoDto(
                    id = "frm_1",
                    sessionId = "ses_1",
                    title = "Trip",
                    fields =
                        listOf(
                            V2FormFieldDto(
                                key = "interests",
                                title = "Interests",
                                type = "multiselect",
                                options =
                                    listOf(
                                        V2FormFieldOptionDto(value = "nature", label = "Scenery & nature"),
                                        V2FormFieldOptionDto(value = "wildlife", label = "Wildlife"),
                                    ),
                            ),
                        ),
                ),
            )

        assertTrue(request.questions.single().multiple)
        val answer =
            adapter.formAnswer(
                request.questions,
                listOf(listOf("Scenery & nature", "Wildlife")),
            )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("nature"), JsonPrimitive("wildlife"))),
            answer.getValue("interests").jsonArray,
        )
    }
}
