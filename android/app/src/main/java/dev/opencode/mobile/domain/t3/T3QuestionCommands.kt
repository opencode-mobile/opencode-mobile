package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.QuestionInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Commands for a `user_input` runtime request (the T3 question prompt). */
object T3QuestionCommands {
    /** `runtime-request.respond` carrying `answers` keyed by question id, like the T3 web client. */
    fun userInputRespond(
        threadId: String,
        requestId: String,
        answers: JsonObject,
        commandId: String = T3Commands.commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "runtime-request.respond")
            put("commandId", commandId)
            put("threadId", threadId)
            put("requestId", requestId)
            put("answers", answers)
        }

    fun userInputDismiss(
        threadId: String,
        requestId: String,
        commandId: String = T3Commands.commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.user-input.dismiss")
            put("commandId", commandId)
            put("threadId", threadId)
            put("requestId", requestId)
        }

    /**
     * Builds the `answers` record: question id -> selected option value, or the
     * option values as an array for a multi-select question. A label the question
     * does not offer is the user's custom text. Questions without a draft are
     * skipped; a question without an id cannot be answered.
     */
    fun encodeAnswers(
        questions: List<QuestionInfo>,
        answers: List<List<String>>,
    ): JsonObject =
        buildJsonObject {
            questions.forEachIndexed { index, question ->
                val key = question.fieldKey?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val picks = answers.getOrNull(index).orEmpty().map { label -> optionValue(question, label) }
                if (picks.isEmpty()) return@forEachIndexed
                val value: JsonElement =
                    if (question.multiple) {
                        JsonArray(picks.map(::JsonPrimitive))
                    } else {
                        JsonPrimitive(picks.first())
                    }
                put(key, value)
            }
        }

    private fun optionValue(
        question: QuestionInfo,
        label: String,
    ): String = question.options.firstOrNull { it.label == label || it.value == label }?.value ?: label
}
