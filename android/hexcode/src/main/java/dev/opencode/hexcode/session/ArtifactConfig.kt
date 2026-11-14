package dev.opencode.hexcode.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One configuration document from `GET /api/config`: its file ([path], none for `OPENCODE_CONFIG_CONTENT`) and parsed [info]. */
data class ConfigDocument(
    val path: String?,
    val info: JsonObject,
) {
    /** The document's `experimental` block, when it has one at all. */
    val experimental: JsonObject? get() = info["experimental"] as? JsonObject
}

/**
 * `GET /api/config?location[directory]=<dir>` (artifacts-enable-spike.md §1.5): the documents from lowest to highest
 * priority, and the global config folder ([directory]), listed even when it holds no file. The `info` is normalized and
 * can hold API keys in plain text: never log it.
 */
data class ConfigDocuments(
    val documents: List<ConfigDocument>,
    val directory: String?,
) {
    /** The document read from [path], if the server could read that file. */
    fun document(path: String): ConfigDocument? = documents.lastOrNull { it.path == path }

    companion object {
        fun read(body: JsonElement): ConfigDocuments? {
            val entries = body as? JsonArray ?: return null
            val documents = mutableListOf<ConfigDocument>()
            var directory: String? = null
            entries.forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val path = (entry["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                when ((entry["type"] as? JsonPrimitive)?.content) {
                    "document" -> documents += ConfigDocument(path, entry["info"] as? JsonObject ?: JsonObject(emptyMap()))
                    "directory" -> if (path != null) directory = path
                }
            }
            return ConfigDocuments(documents, directory)
        }
    }
}

/**
 * The file the setup changes (artifacts-enable-spike.md §3.1): the last document whose `info.experimental` is set wins
 * (`Config.latest`, no merge); with none, the global folder's `opencode.json`, or `opencode.jsonc` when that's the one
 * there. A winner without a path is `OPENCODE_CONFIG_CONTENT`, which only a person can change.
 */
sealed interface ConfigTarget {
    /** The line a person adds by hand, inside the file's `experimental` block or as a new one. */
    val line: String

    data class File(
        val path: String,
        /** The file already has an `experimental` block, so the line goes inside it. */
        val hasBlock: Boolean,
        /** The file is the global one (not a project's own, which overrides it). */
        val global: Boolean,
    ) : ConfigTarget {
        override val line: String get() = if (hasBlock) ArtifactPrompts.LINE else ArtifactPrompts.BLOCK_LINE

        /** The file's name, for "Edit opencode.json". */
        val name: String get() = path.substringAfterLast('/')
    }

    /** `OPENCODE_CONFIG_CONTENT` wins: add the line there and restart OpenCode. */
    data object Env : ConfigTarget {
        override val line: String get() = ArtifactPrompts.LINE
    }

    companion object {
        fun of(config: ConfigDocuments): ConfigTarget? {
            val winner = config.documents.lastOrNull { it.experimental != null }
            val directory = config.directory?.trimEnd('/')
            if (winner != null) {
                val path = winner.path ?: return Env
                return File(path, hasBlock = true, global = directory != null && path.substringBeforeLast('/') == directory)
            }
            directory ?: return null
            val jsonc = "$directory/opencode.jsonc"
            val path = if (config.document(jsonc) != null) jsonc else "$directory/opencode.json"
            return File(path, hasBlock = false, global = true)
        }
    }
}

/** Whether `experimental.artifacts` is `true` in [config]'s winning document, as the server reads it. */
fun ConfigDocuments.artifactsSet(): Boolean =
    (documents.lastOrNull { it.experimental != null }?.experimental?.get("artifacts") as? JsonPrimitive)?.booleanOrNull == true

/** What changed in the config besides the artifacts flag (app-plan.md, Artifacts (M8) › A broken config). */
data class ConfigDamage(
    /** The changed file no longer parses (or is empty): OpenCode ignores all of it. */
    val unreadable: Boolean = false,
    /** Keys outside `experimental.artifacts` that went missing or changed: `provider`, `experimental.todo`. */
    val missing: List<String> = emptyList(),
) {
    val any: Boolean get() = unreadable || missing.isNotEmpty()
}

/**
 * The after-check (artifacts-enable-spike.md §3.2): every document's `info` other than `experimental` must equal the
 * saved copy, and `experimental` must keep its other keys. The target's document gone while its file is there means the
 * edit broke it.
 */
object ConfigCheck {
    fun compare(
        before: ConfigDocuments,
        after: ConfigDocuments,
        target: String,
        /** The target file is on the computer now (read with `fs/read`), so a missing document means it doesn't parse. */
        targetExists: Boolean,
    ): ConfigDamage {
        val missing = mutableListOf<String>()
        var unreadable = false
        before.documents.forEach { old ->
            val new = after.documents.firstOrNull { it.path == old.path }
            if (new == null) {
                if (old.path == target) unreadable = true else missing += old.path ?: "OPENCODE_CONFIG_CONTENT"
                return@forEach
            }
            missing += changedKeys(old.info, new.info)
        }
        if (!unreadable && targetExists && after.document(target) == null) unreadable = true
        return ConfigDamage(unreadable, missing.distinct())
    }

    /** The keys of [old] that [new] lost or changed, `experimental.artifacts` aside. */
    private fun changedKeys(
        old: JsonObject,
        new: JsonObject,
    ): List<String> {
        val changed = mutableListOf<String>()
        old.forEach { (key, value) ->
            if (key == "experimental") {
                val oldBlock = value as? JsonObject ?: return@forEach
                val newBlock = new[key] as? JsonObject
                oldBlock.forEach { (inner, innerValue) ->
                    if (inner != "artifacts" && newBlock?.get(inner) != innerValue) changed += "experimental.$inner"
                }
            } else if (new[key] != value) {
                changed += key
            }
        }
        return changed
    }
}

/**
 * The setup session's words (artifacts-enable-spike.md §2.1, verbatim) and its lockdown (§2.2), and the agent's rule
 * for today's session while artifacts are on (artifacts-proposal.md §2.4). Raise a version whenever its text changes.
 */
object ArtifactPrompts {
    const val PROMPTS_VERSION = 1

    /** The setup session's title; never the daily "YYYY-MM-DD (n)" pattern. */
    const val SETUP_TITLE = "Hex Code setup"

    /** The line a person adds inside an existing `experimental` block. */
    const val LINE = "\"artifacts\": true"

    /** The block a person adds when the file has none. */
    const val BLOCK_LINE = "\"experimental\": { \"artifacts\": true }"

    /** [file] is the absolute path from `GET /api/config`, never `~`. */
    fun turnOn(file: String): String =
        "Turn on OpenCode's experimental artifacts for me. In `$file`, add `\"artifacts\": true` inside the `\"experimental\"` object. " +
            "If there's no `\"experimental\"` key, add `\"experimental\": { \"artifacts\": true }`; if the file doesn't exist, create it " +
            "with just that. Change nothing else: keep every other setting, comment and the formatting as they are. Don't run " +
            "commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>."

    fun turnOff(file: String): String =
        "Turn off OpenCode's experimental artifacts for me. In `$file`, remove `\"artifacts\": true` from the `\"experimental\"` " +
            "object. Change nothing else: keep every other setting, comment and the formatting as they are. Don't run commands, " +
            "look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>."

    /** One permission rule of a session (`Permission.Rule`). */
    data class Rule(
        val action: String,
        val resource: String,
        val effect: String,
    )

    /**
     * The setup session's rules: no shell, code mode, web, skills or subagents; outside the project only the global
     * config folder ([globalDir]); every edit asks. They come last, so they win; a deny for `*` also hides the tool.
     */
    fun lockdown(globalDir: String): List<Rule> =
        listOf(
            Rule("shell", "*", "deny"),
            Rule("execute", "*", "deny"),
            Rule("webfetch", "*", "deny"),
            Rule("websearch", "*", "deny"),
            Rule("skill", "*", "deny"),
            Rule("subagent", "*", "deny"),
            Rule("external_directory", "*", "deny"),
            Rule("external_directory", "${globalDir.trimEnd('/')}/*", "allow"),
            Rule("edit", "*", "ask"),
        )

    /** The instruction entry's id on today's session, put only while artifacts are on. */
    const val RULES_ID = "artifact-rules"

    /** Raised whenever [RULES] changes. */
    const val RULES_VERSION = 1

    val RULES =
        "The user reads this session on a phone, which shows the newest artifact of this run as a tile they can open. " +
            "When they want to see something you made (a page, a mock-up, a chart, a report), publish it as an artifact: " +
            "inside `execute`, call `tools.opencode.artifact_create`; for a new version of the same thing, call " +
            "`tools.opencode.artifact_update` with its id. Never write an artifact's address or URL: the phone opens it. " +
            "These are OpenCode's own artifact tools, not another publishing tool or skill."

    /** The suggestion chip's draft once artifacts are on. */
    const val PAGE_ASK = "Make a small page that previews "
}
