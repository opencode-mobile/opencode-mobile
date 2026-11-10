package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/*
 * Typed reads of a tool part's wire `input` and `metadata`. The raw JSON stays on
 * [MessagePart.Tool] (the session cache and both protocol adapters store it); feature UI reads
 * these accessors and never indexes the JSON by key.
 *
 * Accessors keep the exact rule each timeline surface used before they moved here, including
 * small differences between surfaces (a JSON `null` value, numbers given where a string is
 * expected), so every tool renders as it did.
 */

// Tool card and step chrome (timeline blocks). A present primitive counts by its content, so a
// number reads as its digits.

/** Search pattern (glob, grep). */
val MessagePart.Tool.inputPattern: String? get() = primitiveContent(input, "pattern")

/** Directory a search or listing runs in. */
val MessagePart.Tool.inputSearchPath: String? get() = primitiveContent(input, "path", "directory")

/** Web search or LSP query. */
val MessagePart.Tool.inputQuery: String? get() = primitiveContent(input, "query")

/** Fetched URL. */
val MessagePart.Tool.inputUrl: String? get() = primitiveContent(input, "url")

/** Shell command. */
val MessagePart.Tool.inputCommand: String? get() = primitiveContent(input, "command")

/** Title the model gave the call. */
val MessagePart.Tool.inputTitle: String? get() = primitiveContent(input, "title")

/** Description the model gave the call. */
val MessagePart.Tool.inputDescription: String? get() = primitiveContent(input, "description")

/** A subagent task's brief: its description, else its prompt. */
val MessagePart.Tool.inputTaskBrief: String? get() = primitiveContent(input, "description", "prompt")

/** A single question asked without the `questions` list. */
val MessagePart.Tool.inputQuestionPrompt: String? get() = primitiveContent(input, "prompt", "text", "question")

/** Skill the model asked to load. */
val MessagePart.Tool.inputSkillId: String? get() = primitiveContent(input, "id", "name", "skill")

/** Subagent the task asked for. */
val MessagePart.Tool.inputAgent: String? get() = primitiveContent(input, "agent", "subagent_type")

/** LSP operation. */
val MessagePart.Tool.inputOperation: String? get() = primitiveContent(input, "operation")

/** LSP line. */
val MessagePart.Tool.inputLine: String? get() = primitiveContent(input, "line")

/** File an edit, write or LSP call targets (`filePath`, then `path`, then `file`). */
val MessagePart.Tool.inputTargetPath: String? get() = primitiveContent(input, "filePath", "path", "file")

/** File a read opens (`filePath`, then `file`, then `path`): v1 sends `filePath`, v2 `path`. */
val MessagePart.Tool.inputReadPath: String? get() = primitiveContent(input, "filePath", "file", "path")

/** Body a write puts in the file. */
val MessagePart.Tool.writeContent: String?
    get() = (input?.get("content") as? JsonPrimitive)?.contentOrNull

/** One-line preview of the call's arguments, for tools without their own chrome. */
val MessagePart.Tool.inputPreview: String? get() = inputPreview(input)

/** Questions a question tool asked, in order. */
val MessagePart.Tool.inputQuestions: List<String>
    get() {
        val array = input?.get("questions") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            (obj["question"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        }
    }

/** Answers a question tool reports; a multi-select answer joins its choices with commas. */
val MessagePart.Tool.questionAnswers: List<String>
    get() {
        val array = metadata?.get("answers") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonArray ->
                    element
                        .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { text -> text.isNotEmpty() } }
                        .joinToString(", ")
                        .takeIf { it.isNotEmpty() }
                is JsonPrimitive -> element.content.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

/** Agent the server says ran the task. */
val MessagePart.Tool.metadataAgent: String? get() = primitiveContent(metadata, "agent")

/** Name of the loaded skill. */
val MessagePart.Tool.skillName: String? get() = primitiveContent(metadata, "name")

/** Directory the loaded skill lives in. */
val MessagePart.Tool.skillDirectory: String? get() = primitiveContent(metadata, "directory")

/** Child session a subagent task runs in. */
val MessagePart.Tool.childSessionId: String? get() = primitiveContent(metadata, "sessionId", "sessionID")

/** The task runs in the background (`metadata.background` true or `"1"`). */
val MessagePart.Tool.isBackground: Boolean get() = isTruthy(metadata, "background")

/** A write replaced a file that already existed. */
val MessagePart.Tool.fileExisted: Boolean get() = isTruthy(metadata, "exists")

/** How many child tool calls a task reports in `metadata.summary`, or null when none. */
val MessagePart.Tool.summaryCallCount: Int?
    get() = (metadata?.get("summary") as? JsonArray)?.size?.takeIf { it > 0 }

/** Matches a grep reports. */
val MessagePart.Tool.grepMatchCount: Int? get() = metadata?.let { intIn(it, "matches") }

/** Files a glob reports. */
val MessagePart.Tool.globFileCount: Int? get() = metadata?.let { intIn(it, "count") }

/** Shell exit code. */
val MessagePart.Tool.exitCode: Int?
    get() = (metadata?.get("exit") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/** One file a patch or edit changed, from `metadata.files`. */
data class ToolPatchFile(
    val path: String,
    val additions: Int?,
    val deletions: Int?,
)

/** Size of `metadata.files`, counting every entry, or null when there is no list. */
val MessagePart.Tool.patchFileCount: Int?
    get() = (metadata?.get("files") as? JsonArray)?.size

/** Files in `metadata.files` that name a path. */
val MessagePart.Tool.patchFiles: List<ToolPatchFile>
    get() {
        val array = metadata?.get("files") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = nonEmptyContent(obj, "filePath", "relativePath", "file") ?: return@mapNotNull null
            ToolPatchFile(
                path = path,
                additions = intIn(obj, "additions"),
                deletions = intIn(obj, "deletions"),
            )
        }
    }

/** File named by `metadata.filediff`. */
val MessagePart.Tool.filediffPath: String?
    get() = (metadata?.get("filediff") as? JsonObject)?.let { nonEmptyContent(it, "file") }

/** Lines `metadata.filediff` adds. */
val MessagePart.Tool.filediffAdditions: Int?
    get() = (metadata?.get("filediff") as? JsonObject)?.let { intIn(it, "additions") }

/** Lines `metadata.filediff` deletes. */
val MessagePart.Tool.filediffDeletions: Int?
    get() = (metadata?.get("filediff") as? JsonObject)?.let { intIn(it, "deletions") }

/** Calls a batch execute ran (`metadata.toolCalls`), each as `name preview`. */
val MessagePart.Tool.executeCallPreviews: List<String>
    get() {
        val array = metadata?.get("toolCalls") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["tool"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val preview = inputPreview(obj["input"] as? JsonObject)
            if (preview != null) "$name $preview" else name
        }
    }

// Tool result sheet. A JSON `null` reads as absent.

/** What the tool result sheet reads from a call. */
data class ToolResultFields(
    val command: String?,
    val path: String?,
    val pattern: String?,
    val searchPath: String?,
    val offset: Int?,
    val limit: Int?,
    val content: String?,
    val exitCode: Int?,
    val oldString: String?,
    val newString: String?,
    /** Unified diff from `metadata.filediff.patch`, else the first `metadata.files[].patch`. */
    val metadataPatch: String?,
)

val MessagePart.Tool.resultFields: ToolResultFields
    get() =
        ToolResultFields(
            command = nonEmptyContent(input, "command"),
            path = nonEmptyContent(input, "filePath", "file", "path"),
            pattern = nonEmptyContent(input, "pattern"),
            searchPath = nonEmptyContent(input, "path", "directory"),
            offset = (input?.get("offset") as? JsonPrimitive)?.intOrNull,
            limit = (input?.get("limit") as? JsonPrimitive)?.intOrNull,
            content = nonEmptyContent(input, "content"),
            exitCode = (metadata?.get("exit") as? JsonPrimitive)?.intOrNull,
            oldString = nonEmptyContent(input, "oldString"),
            newString = nonEmptyContent(input, "newString"),
            metadataPatch = metadataPatch(),
        )

private fun MessagePart.Tool.metadataPatch(): String? {
    val metadata = metadata
    nonEmptyContent(metadata?.get("filediff") as? JsonObject, "patch")?.let { return it }
    val files = metadata?.get("files") as? JsonArray
    files?.forEach { element ->
        nonEmptyContent(element as? JsonObject, "patch")?.let { return it }
    }
    return null
}

// Child session linking. A JSON `null` reads as absent.

/** Child session id the part's metadata names (`sessionID`, then `sessionId`). */
val MessagePart.Tool.linkedChildSessionId: String?
    get() = nonEmptyContent(metadata, "sessionID") ?: nonEmptyContent(metadata, "sessionId")

/** Session id the call's input names. */
val MessagePart.Tool.inputSessionId: String? get() = nonEmptyContent(input, "sessionID")

/** Description, for matching a task to its child session. */
val MessagePart.Tool.linkDescription: String? get() = nonEmptyContent(input, "description")

/** This part with [id] recorded as its child session, keeping its other metadata. */
fun MessagePart.Tool.withChildSessionId(id: String): MessagePart.Tool =
    copy(metadata = JsonObject((metadata ?: JsonObject(emptyMap())) + ("sessionID" to JsonPrimitive(id))))

// Background work panel. Only JSON strings count.

/** A subagent task's description. */
val MessagePart.Tool.subagentDescription: String? get() = jsonString(input, "description")

/** Agent a subagent task names: input `agent`, then `subagent_type`, then metadata `agent`. */
val MessagePart.Tool.subagentAgentName: String?
    get() = jsonString(input, "agent") ?: jsonString(input, "subagent_type") ?: jsonString(metadata, "agent")

/** Child session a subagent task runs in (`sessionID`, then `sessionId`). */
val MessagePart.Tool.subagentSessionId: String?
    get() = jsonString(metadata, "sessionID") ?: jsonString(metadata, "sessionId")

/**
 * Child tool calls a task part reports in `metadata.summary` (classic v1 up to 1.1.x sends the
 * child's tool parts there; later v1 and v2 send none), as tool parts. Null when the part
 * carries no summary list.
 */
val MessagePart.Tool.summaryCalls: List<MessagePart.Tool>?
    get() {
        val summary = metadata?.get("summary") as? JsonArray ?: return null
        return summary.mapIndexedNotNull { index, element ->
            val part = runCatching { element.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            val state = part["state"] as? JsonObject
            val name = jsonString(part, "tool") ?: return@mapIndexedNotNull null
            MessagePart.Tool(
                toolName = name,
                status =
                    when (jsonString(state, "status")) {
                        "pending" -> ToolStatus.Pending
                        "running" -> ToolStatus.Running
                        "error" -> ToolStatus.Error
                        else -> ToolStatus.Completed
                    },
                title = jsonString(state, "title"),
                input = state?.get("input") as? JsonObject,
                id = jsonString(part, "id") ?: "summary-$index",
            )
        }
    }

/** First non-empty primitive content under [keys]; a JSON `null` reads as `"null"`. */
private fun primitiveContent(
    obj: JsonObject?,
    vararg keys: String,
): String? {
    if (obj == null) return null
    for (key in keys) {
        val primitive = obj[key] as? JsonPrimitive ?: continue
        val value = primitive.content.takeIf { it.isNotEmpty() } ?: continue
        return value
    }
    return null
}

/** First non-empty primitive content under [keys]; a JSON `null` is skipped. */
private fun nonEmptyContent(
    obj: JsonObject?,
    vararg keys: String,
): String? {
    if (obj == null) return null
    for (key in keys) {
        val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        if (value != null) return value
    }
    return null
}

/** Non-empty JSON string under [key]. */
private fun jsonString(
    obj: JsonObject?,
    key: String,
): String? = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun intIn(
    obj: JsonObject,
    key: String,
): Int? = (obj[key] as? JsonPrimitive)?.intOrNull

private fun isTruthy(
    metadata: JsonObject?,
    key: String,
): Boolean {
    val primitive = metadata?.get(key) as? JsonPrimitive ?: return false
    primitive.booleanOrNull?.let { return it }
    val content = primitive.content.lowercase()
    return content == "true" || content == "1"
}

private fun inputPreview(input: JsonObject?): String? {
    if (input == null) return null
    primitiveContent(
        input,
        "command",
        "filePath",
        "path",
        "file",
        "url",
        "query",
        "pattern",
        "name",
        "title",
        "description",
        "prompt",
        "text",
        "id",
    )?.let { return it }
    val parts =
        input.mapNotNull { (key, value) ->
            if (key == ToolArgumentsPreview.INPUT_KEY) return@mapNotNull null
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            val text = primitive.content.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return@mapNotNull null
            "$key=$text"
        }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "[$it]" }
}
