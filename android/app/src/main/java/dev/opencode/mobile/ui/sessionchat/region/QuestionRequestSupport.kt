package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session

internal fun orderedQuestionRequests(
    rootSessionId: String,
    sessions: List<Session>,
    requests: List<QuestionRequest>,
): List<QuestionRequest> {
    val children = sessions.groupBy { it.parentId }
    val sessionOrder =
        buildList {
            val queue = ArrayDeque<String>()
            val visited = mutableSetOf<String>()
            queue.add(rootSessionId)
            while (queue.isNotEmpty()) {
                val sessionId = queue.removeFirst()
                if (!visited.add(sessionId)) continue
                add(sessionId)
                children[sessionId].orEmpty().forEach { queue.add(it.id) }
            }
        }
    val requestsBySession = requests.groupBy { it.sessionId }
    return sessionOrder.flatMap { requestsBySession[it].orEmpty() }
}

internal fun dropOrphanQuestionRequests(
    requests: List<QuestionRequest>,
    messages: List<Message>,
): List<QuestionRequest> {
    if (requests.isEmpty() || messages.isEmpty()) return requests
    val messagesById = messages.associateBy { it.id }
    return requests.filterNot { request ->
        val messageId = request.toolMessageId ?: return@filterNot false
        val callId = request.toolCallId ?: return@filterNot false
        val message = messagesById[messageId] ?: return@filterNot false
        message.parts.any { part ->
            part is MessagePart.Tool &&
                part.toolName.equals("question", ignoreCase = true) &&
                part.callId == callId &&
                (part.status == ToolStatus.Completed || part.status == ToolStatus.Error)
        }
    }
}

data class QuestionAnswerDraft(
    val selectedLabels: List<String> = emptyList(),
    val customText: String = "",
    val customSelected: Boolean = false,
)

internal fun isQuestionAnswerValid(
    question: QuestionInfo,
    draft: QuestionAnswerDraft,
): Boolean =
    if (draft.customSelected) {
        question.custom && draft.customText.isNotBlank()
    } else {
        draft.selectedLabels.isNotEmpty()
    }

internal fun buildQuestionAnswers(
    questions: List<QuestionInfo>,
    drafts: Map<Int, QuestionAnswerDraft>,
): List<List<String>>? {
    val answers = ArrayList<List<String>>(questions.size)
    questions.forEachIndexed { index, question ->
        val draft = drafts[index] ?: QuestionAnswerDraft()
        if (!isQuestionAnswerValid(question, draft)) return null
        answers +=
            if (draft.customSelected) {
                listOf(draft.customText.trim())
            } else {
                draft.selectedLabels
            }
    }
    return answers
}
