package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionRequestSupportTest {
    @Test
    fun orderedQuestionRequests_prioritizesRootAndIncludesNewDescendants() {
        val ordered =
            orderedQuestionRequests(
                rootSessionId = "root",
                sessions =
                    listOf(
                        Session(id = "child", title = "Child", parentId = "root"),
                        Session(id = "new-child", title = "New child", parentId = "root"),
                    ),
                requests =
                    listOf(
                        questionRequest("root-request", "root"),
                        questionRequest("child-request", "child"),
                        questionRequest("new-child-request", "new-child"),
                    ),
            )

        assertEquals(
            listOf("root-request", "child-request", "new-child-request"),
            ordered.map { it.id },
        )
    }

    @Test
    fun dropOrphanQuestionRequests_removesCompletedToolCorrelation() {
        val pending =
            questionRequest(
                id = "que_1",
                sessionId = "ses_1",
                toolMessageId = "msg_1",
                toolCallId = "call_1",
            )
        val kept =
            questionRequest(
                id = "que_2",
                sessionId = "ses_1",
                toolMessageId = "msg_2",
                toolCallId = "call_2",
            )
        val messages =
            listOf(
                Message(
                    id = "msg_1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            MessagePart.Tool(
                                toolName = "question",
                                status = ToolStatus.Completed,
                                callId = "call_1",
                            ),
                        ),
                ),
                Message(
                    id = "msg_2",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            MessagePart.Tool(
                                toolName = "question",
                                status = ToolStatus.Running,
                                callId = "call_2",
                            ),
                        ),
                ),
            )

        assertEquals(
            listOf("que_2"),
            dropOrphanQuestionRequests(listOf(pending, kept), messages).map { it.id },
        )
    }

    @Test
    fun buildQuestionAnswers_requiresEveryQuestion() {
        val questions =
            listOf(
                QuestionInfo(
                    question = "One",
                    header = "One",
                    options = listOf(QuestionOption("A", "")),
                ),
                QuestionInfo(
                    question = "Two",
                    header = "Two",
                    options = listOf(QuestionOption("B", "")),
                    custom = true,
                ),
            )
        assertNull(
            buildQuestionAnswers(
                questions,
                mapOf(0 to QuestionAnswerDraft(selectedLabels = listOf("A"))),
            ),
        )
        assertEquals(
            listOf(listOf("A"), listOf("custom")),
            buildQuestionAnswers(
                questions,
                mapOf(
                    0 to QuestionAnswerDraft(selectedLabels = listOf("A")),
                    1 to QuestionAnswerDraft(customSelected = true, customText = "custom"),
                ),
            ),
        )
    }

    private fun questionRequest(
        id: String,
        sessionId: String,
        toolMessageId: String? = null,
        toolCallId: String? = null,
    ) = QuestionRequest(
        id = id,
        sessionId = sessionId,
        questions =
            listOf(
                QuestionInfo(
                    question = "Q",
                    header = "H",
                    options = listOf(QuestionOption("A", "desc")),
                ),
            ),
        toolMessageId = toolMessageId,
        toolCallId = toolCallId,
    )
}
