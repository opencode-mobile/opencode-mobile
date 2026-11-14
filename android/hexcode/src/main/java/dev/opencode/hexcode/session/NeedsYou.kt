package dev.opencode.hexcode.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What an approval asks (app-plan.md, Needs you and control › Approvals): a `permission.asked` request, or one from
 * `GET /api/session/{id}/permission`, as OpenCode v2.2.5 sends it (m4-proposal.md §1.1).
 */
data class ApprovalRequest(
    /** `shell`, `edit`, `webfetch`, `subagent`, or another tool's action. */
    val action: String,
    /** The commands (one per parsed command), the file paths, the URL or the agent id. */
    val resources: List<String> = emptyList(),
    /** What Always stores, one saved rule per pattern: `ls *`, or `*` for every edit. */
    val save: List<String> = emptyList(),
    /** Set only by plugins. */
    val message: String? = null,
    /** An edit's files, with their diffs (`metadata.files`). */
    val files: List<ApprovalFile> = emptyList(),
) {
    companion object {
        /** The request in a `permission.asked` event's data, or one listed item. */
        fun read(data: JsonObject): ApprovalRequest =
            ApprovalRequest(
                action = data.string("action").orEmpty(),
                resources = data.strings("resources"),
                save = data.strings("save"),
                message = data.string("message")?.ifBlank { null },
                files = ApprovalFile.read(data["metadata"] as? JsonObject),
            )
    }
}

/** One file of an edit to approve: its path and its unified diff, when the request carries one. */
data class ApprovalFile(
    val path: String,
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
) {
    companion object {
        /** `metadata.files[]`: `{file, patch, status, additions, deletions}`. */
        fun read(metadata: JsonObject?): List<ApprovalFile> =
            (metadata?.get("files") as? JsonArray)
                ?.mapNotNull { element ->
                    val file = element as? JsonObject ?: return@mapNotNull null
                    val path = file.string("file") ?: file.string("path") ?: return@mapNotNull null
                    ApprovalFile(path, file.string("patch")?.ifBlank { null }, file.int("additions"), file.int("deletions"))
                }.orEmpty()
    }
}

/** A line of the request's code block: plain, or a diff's added or removed line. */
data class CodeLine(
    val text: String,
    val kind: Kind = Kind.Plain,
) {
    enum class Kind { Plain, Added, Removed, More }
}

/**
 * What the approval's `HexCode` block shows (app-plan.md, Approvals › What it shows): shell commands one per line;
 * the URL; the agent id; for edits each file's path, then its diff (at most [DIFF_LINES] lines, then a "More" line
 * carrying how many were left out), or just the paths when there's no diff. Empty when there's nothing to show.
 */
object ApprovalLines {
    const val DIFF_LINES = 12

    fun of(request: ApprovalRequest): List<CodeLine> {
        if (request.action != "edit" || request.files.isEmpty()) {
            return request.resources.filter { it.isNotBlank() }.map { CodeLine(it) }
        }
        return request.files.flatMap { file ->
            val diff = file.patch?.let(::diffLines).orEmpty()
            val shown = diff.take(DIFF_LINES)
            val more = diff.size - shown.size
            listOf(CodeLine(file.path)) + shown + if (more > 0) listOf(CodeLine(more.toString(), CodeLine.Kind.More)) else emptyList()
        }
    }

    /** A unified diff's added and removed lines only: no headers (`Index:`, `===`, `---`, `+++`, `@@`), no context. */
    fun diffLines(patch: String): List<CodeLine> {
        val body = mutableListOf<CodeLine>()
        var inHunk = false
        for (line in patch.lines()) {
            when {
                line.startsWith("@@") -> inHunk = true
                !inHunk -> Unit
                line.startsWith("+") -> body += CodeLine(line, CodeLine.Kind.Added)
                line.startsWith("-") -> body += CodeLine(line, CodeLine.Kind.Removed)
            }
        }
        return body
    }
}

/** What the Always line says: the patterns it stores, or every action of its kind (`save` is `["*"]`). */
sealed interface AlwaysScope {
    /** "Always allows `ls *`, `git *` in this project, from now on." */
    data class Patterns(
        val patterns: List<String>,
    ) : AlwaysScope

    /** "Always allows any edit in this project, from now on." [action] is the request's action, as given. */
    data class Any(
        val action: String,
    ) : AlwaysScope

    companion object {
        /**
         * Null when Always isn't offered: nothing to save, or the project is `global` (a folder without a git
         * repository, whose rules would apply to every such folder), or the project isn't known yet.
         */
        fun of(
            request: ApprovalRequest,
            projectId: String?,
        ): AlwaysScope? {
            val save = request.save.filter { it.isNotBlank() }
            return when {
                save.isEmpty() || projectId == null || projectId == GLOBAL_PROJECT -> null
                save == listOf("*") -> Any(request.action)
                else -> Patterns(save)
            }
        }

        /** The project id OpenCode gives a folder that isn't a git repository with commits. */
        const val GLOBAL_PROJECT = "global"
    }
}

/** How the user answered an approval: the body's `decision`. */
enum class ApprovalDecision(
    val wire: String,
) {
    Once("once"),
    Always("always"),

    /** Always with a note ([SessionPrompts.DENY_NOTE] or the spoken one), so the agent carries on another way. */
    Reject("reject"),
}

/** A form the agent opened: the question tool's, or another kind (an MCP form), which the phone can't answer. */
data class QuestionForm(
    val title: String? = null,
    val fields: List<QuestionField> = emptyList(),
) {
    /** Every field is a single (`string`) or multiple (`multiselect`) choice: the question tool's. */
    val answerable: Boolean get() =
        fields.isNotEmpty() &&
            fields.all { it.type == QuestionField.SINGLE || it.type == QuestionField.MULTIPLE }

    companion object {
        /** A `form.created` event's `data.form`, or one listed form. */
        fun read(form: JsonObject): QuestionForm =
            QuestionForm(
                title = form.string("title")?.ifBlank { null },
                fields =
                    (form["fields"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonObject)?.let(QuestionField::read) }
                        .orEmpty(),
            )
    }
}

/** One question: its header ([title]), the question ([description]), its choices, and whether an own answer is allowed. */
data class QuestionField(
    val key: String,
    val title: String? = null,
    val description: String? = null,
    val type: String = SINGLE,
    val options: List<QuestionOption> = emptyList(),
    val custom: Boolean = false,
) {
    val multiple: Boolean get() = type == MULTIPLE

    companion object {
        const val SINGLE = "string"
        const val MULTIPLE = "multiselect"

        fun read(field: JsonObject): QuestionField? {
            val key = field.string("key") ?: return null
            return QuestionField(
                key = key,
                title = field.string("title")?.ifBlank { null },
                description = field.string("description")?.ifBlank { null },
                type = field.string("type") ?: SINGLE,
                options =
                    (field["options"] as? JsonArray)
                        ?.mapNotNull { element ->
                            val option = element as? JsonObject ?: return@mapNotNull null
                            val label = option.string("label") ?: option.string("value") ?: return@mapNotNull null
                            QuestionOption(option.string("value") ?: label, label, option.string("description")?.ifBlank { null })
                        }.orEmpty(),
                custom = (field["custom"] as? JsonPrimitive)?.content == "true",
            )
        }
    }
}

/** A choice: what the answer sends ([value]) and what the option shows. */
data class QuestionOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/** One page's answer: the chosen options' values, and the own answer, which counts while [ownChosen]. */
data class FieldAnswer(
    val chosen: List<String> = emptyList(),
    val own: String? = null,
    val ownChosen: Boolean = false,
) {
    /** Next and Submit need one. */
    val answered: Boolean get() = chosen.isNotEmpty() || (ownChosen && !own.isNullOrBlank())
}

/**
 * The question sheet's answers, a page per field (app-plan.md, Questions). Single choice: tapping an option chooses it,
 * and a spoken own answer replaces the choice. Multiselect: options toggle, and the own answer is one more choice.
 * Going Back keeps what was answered.
 */
data class QuestionAnswers(
    val form: QuestionForm,
    val page: Int = 0,
    val answers: Map<String, FieldAnswer> = emptyMap(),
) {
    val question: QuestionField get() = form.fields[page]
    val answer: FieldAnswer get() = answers[question.key] ?: FieldAnswer()
    val last: Boolean get() = page == form.fields.lastIndex

    fun choose(value: String): QuestionAnswers {
        val current = answer
        val next =
            if (question.multiple) {
                current.copy(chosen = if (value in current.chosen) current.chosen - value else current.chosen + value)
            } else {
                current.copy(chosen = listOf(value), ownChosen = false)
            }
        return copy(answers = answers + (question.key to next))
    }

    /** A spoken own answer arrived: it's chosen (in single choice, in place of the option chosen). */
    fun own(text: String): QuestionAnswers {
        val current = answer
        val next = if (question.multiple) current.copy(own = text, ownChosen = true) else FieldAnswer(own = text, ownChosen = true)
        return copy(answers = answers + (question.key to next))
    }

    /** A tap on the own answer: chooses it (single choice; it counts once it has text), or in multiselect toggles it. */
    fun toggleOwn(): QuestionAnswers {
        val current = answer
        val next =
            if (question.multiple) {
                current.copy(ownChosen = !current.ownChosen)
            } else {
                current.copy(chosen = emptyList(), ownChosen = true)
            }
        return copy(answers = answers + (question.key to next))
    }

    fun next(): QuestionAnswers = if (last || !answer.answered) this else copy(page = page + 1)

    fun back(): QuestionAnswers = if (page == 0) this else copy(page = page - 1)

    /**
     * The reply's `answer`: `{"q0": "Red", "q1": ["Apple", "Kiwi"]}`, a single choice as a string and a multiselect as
     * a string array of the options' values, an own answer as its text (in a multiselect, one more item, last).
     */
    fun body(): JsonObject =
        buildJsonObject {
            form.fields.forEach { field ->
                val answer = answers[field.key] ?: return@forEach
                if (!answer.answered) return@forEach
                val own = answer.own?.takeIf { answer.ownChosen && it.isNotBlank() }
                if (field.multiple) {
                    val values = field.options.map { it.value }.filter { it in answer.chosen } + listOfNotNull(own)
                    put(field.key, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                } else {
                    put(field.key, own ?: answer.chosen.first())
                }
            }
        }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty()
