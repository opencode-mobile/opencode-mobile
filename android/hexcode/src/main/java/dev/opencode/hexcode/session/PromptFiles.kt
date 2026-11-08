package dev.opencode.hexcode.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A file uploaded for a message (app-plan.md, Attach files): its [name], its type as the picker gave it, and the [path]
 * on the computer that the agent gets.
 */
data class PromptFile(
    val name: String,
    val mime: String,
    val path: String,
)

/**
 * How uploaded files travel in a prompt, as OpenCode's own app and OpenCode Mobile send them (attach-files-proposal.md
 * §1.3): one ``Attached file: `<path>` `` line per file after the text, and `metadata.attachments`.
 */
object PromptFiles {
    private const val LINE = "Attached file: `"

    /** OpenCode's own strip: trailing path lines after a line break. */
    private val trailing = Regex("""(\s*\nAttached file: `[^\n]*`)+\s*$""")

    /** A message of only path lines, maybe with the correction label before the first. */
    private val onlyLines = Regex("""^(\[Correction] )?Attached file: `[^\n]*`(\s*\nAttached file: `[^\n]*`)*\s*$""")

    private val path = Regex("""Attached file: `([^\n]*)`""")

    fun line(path: String): String = "$LINE$path`"

    /** [text], then a line break and one [line] per file; with no text, only the lines. */
    fun text(
        text: String,
        files: List<PromptFile>,
    ): String {
        if (files.isEmpty()) return text
        val lines = files.joinToString("\n") { line(it.path) }
        return if (text.isBlank()) lines else "$text\n$lines"
    }

    /**
     * `{"displayText", "comments": [], "attachments": [{name, mime, path}]}`, so OpenCode Mobile and OpenCode's desktop
     * app show the files as chips (the desktop needs `displayText` and `comments`). Null with no files.
     */
    fun metadata(
        displayText: String,
        files: List<PromptFile>,
    ): JsonObject? {
        if (files.isEmpty()) return null
        return buildJsonObject {
            put("displayText", displayText)
            putJsonArray("comments") {}
            putJsonArray("attachments") {
                files.forEach { file ->
                    addJsonObject {
                        put("name", file.name)
                        put("mime", file.mime)
                        put("path", file.path)
                    }
                }
            }
        }
    }

    /** The message as it's shown: without its trailing path lines. A correction of only files keeps its label. */
    fun strip(text: String): String {
        onlyLines.matchEntire(text)?.let { return it.groupValues[1].trimEnd() }
        return text.replace(trailing, "")
    }

    /** The names of the files in [text]'s trailing path lines: each path's last part. */
    fun names(text: String): List<String> {
        val lines = onlyLines.matchEntire(text)?.value ?: trailing.find(text)?.value ?: return emptyList()
        return path.findAll(lines).map { it.groupValues[1].substringAfterLast('/').substringAfterLast('\\') }.toList()
    }
}
