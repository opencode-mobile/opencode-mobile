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
        val count = draft.selectedLabels.size
        count >= maxOf(1, question.minSelections ?: 1) && count <= (question.maxSelections ?: Int.MAX_VALUE)
    }

/**
 * Next selection after tapping [label]. A single-pick question (or a multiselect
 * capped at one) replaces the pick; a multiselect at its `maxItems` cap ignores
 * further picks until one is cleared.
 */
internal fun toggledQuestionSelection(
    question: QuestionInfo,
    selected: List<String>,
    label: String,
): List<String> {
    if (!question.multiple || question.maxSelections == 1) return listOf(label)
    if (label in selected) return selected - label
    val max = question.maxSelections
    if (max != null && selected.size >= max) return selected
    return selected + label
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
