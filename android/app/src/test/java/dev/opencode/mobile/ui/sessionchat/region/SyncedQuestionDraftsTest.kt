package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import dev.opencode.mobile.domain.session.QuestionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedQuestionDraftsTest {
    private val answerA = mapOf(0 to QuestionAnswerDraft(selectedLabels = listOf("A")))
    private val onA =
        SessionChatInboxRegionState(
            rootSessionId = "root",
            questionRequests = listOf(question("question-a"), question("question-b")),
            questionDrafts = answerA,
            questionDraftRequestId = "question-a",
        )

    @Test
    fun pagingAway_keepsTheAnswersAndShowsTheOtherRequestEmpty() {
        val onB = syncedQuestionDrafts(onA.copy(questionSelectedId = "question-b"))

        assertEquals("question-b", onB.questionDraftRequestId)
        assertEquals(emptyMap<Int, QuestionAnswerDraft>(), onB.questionDrafts)
        assertEquals(answerA, onB.keptQuestionDrafts["question-a"]?.drafts)

        val backOnA = syncedQuestionDrafts(onB.copy(questionSelectedId = "question-a"))
        assertEquals(answerA, backOnA.questionDrafts)
        assertEquals(emptyMap<String, KeptQuestionDraft>(), backOnA.keptQuestionDrafts - "question-b")
    }

    @Test
    fun rerunningOnTheSameState_givesTheSameResult() {
        // MutableStateFlow.update re-runs its block when another write lands first.
        val paged = onA.copy(questionSelectedId = "question-b")

        assertEquals(syncedQuestionDrafts(paged), syncedQuestionDrafts(paged))
        val snapshotDuringPage = paged.copy(questionError = "stale")
        assertEquals(emptyMap<Int, QuestionAnswerDraft>(), syncedQuestionDrafts(snapshotDuringPage).questionDrafts)
    }

    @Test
    fun answeredRequest_dropsItsKeptAnswers() {
        val onB = syncedQuestionDrafts(onA.copy(questionSelectedId = "question-b"))
        val afterAnswer = syncedQuestionDrafts(onB.copy(questionRequests = listOf(question("question-b"))))

        assertEquals(emptyMap<String, KeptQuestionDraft>(), afterAnswer.keptQuestionDrafts)
    }

    private fun question(id: String) =
        QuestionRequest(
            id = id,
            sessionId = "root",
            questions =
                listOf(
                    QuestionInfo(
                        question = "Which approach?",
                        header = "Approach",
                        options = listOf(QuestionOption(label = "A", description = "First")),
                    ),
                ),
            toolMessageId = null,
            toolCallId = null,
        )
}
