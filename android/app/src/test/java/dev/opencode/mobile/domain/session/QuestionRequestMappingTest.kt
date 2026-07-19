package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.QuestionInfoDto
import dev.opencode.mobile.data.remote.dto.QuestionOptionDto
import dev.opencode.mobile.data.remote.dto.QuestionRequestDto
import dev.opencode.mobile.data.remote.dto.QuestionToolDto
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
}
