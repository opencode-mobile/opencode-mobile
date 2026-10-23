package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Typed body for the tool-result sheet. Feature code classifies a part into one
 * of these shapes. The sheet renders the shape; it does not re-parse tool ids.
 */
@Serializable
sealed class ToolResultPresentation {
    abstract val title: String
    abstract val raw: String
    abstract val eyebrow: String?

    val copiesOriginal: Boolean
        get() =
            this is FileStatus || this is PathList || this is PullRequest || this is Thought || this is Unrecognized

    @Serializable
    data class FileRow(
        val letter: String,
        val tone: StatusTone,
        val name: String,
        val directory: String?,
        val fullPath: String,
    )

    @Serializable
    enum class StatusTone { Modified, Added, Deleted, Other }

    @Serializable
    data class Count(val label: String, val tone: StatusTone)

    @Serializable
    data class FileStatus(
        val command: String?,
        val exitCode: Int?,
        val durationLabel: String?,
        val files: List<FileRow>,
        val counts: List<Count>,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val eyebrow: String = "Shell"
        override val title: String = countTitle(files.size, "file", "files")
    }

    @Serializable
    data class PathRow(val name: String, val directory: String?, val fullPath: String)

    @Serializable
    data class PathList(
        override val eyebrow: String,
        val command: String?,
        val paths: List<PathRow>,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val title: String = countTitle(paths.size, "path", "paths")
    }

    @Serializable
    data class PullRequest(
        val command: String?,
        val exitCode: Int?,
        val durationLabel: String?,
        val number: Int,
        val url: String,
        val branch: String?,
        val pullTitle: String?,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val eyebrow: String = "Shell"
        override val title: String = "Pull request opened"
    }

    @Serializable
    data class Thought(
        val durationLabel: String?,
        val callout: String,
        val paragraphs: List<String>,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val eyebrow: String = "Thought"
        override val title: String = "Thought"
    }

    @Serializable
    enum class SourceKind { Read, Write }

    @Serializable
    data class SourceLine(val number: Int, val text: String)

    @Serializable
    data class Source(
        val kind: SourceKind,
        val filename: String,
        val language: String?,
        val highlightLanguage: String?,
        val rangeLabel: String?,
        val lines: List<SourceLine>,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val eyebrow: String = if (kind == SourceKind.Read) "Read" else "Write"
        override val title: String = filename
    }

    @Serializable
    data class GrepHit(val line: Int, val text: String)

    @Serializable
    data class GrepFile(
        val name: String,
        val directory: String?,
        val hits: List<GrepHit>,
    )

    @Serializable
    data class Grep(
        val pattern: String,
        val searchPath: String?,
        val matchCount: Int,
        val groups: List<GrepFile>,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val eyebrow: String = "Grep"
        override val title: String = pattern
    }

    @Serializable
    data class Edit(
        override val eyebrow: String,
        val filename: String,
        val additions: Int,
        val deletions: Int,
        val patch: String,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val title: String = filename
    }

    @Serializable
    data class Unrecognized(
        override val title: String,
        val error: String?,
        override val raw: String,
    ) : ToolResultPresentation() {
        override val eyebrow: String? = null
    }

    companion object {
        fun countTitle(count: Int, singular: String, plural: String): String =
            if (count == 1) "1 $singular" else "$count $plural"
    }
}

/** Inputs the classifier needs. Callers extract these from a tool part. */
internal data class ToolResultRequest(
    val toolName: String,
    val fallbackTitle: String,
    val command: String? = null,
    val path: String? = null,
    val pattern: String? = null,
    val searchPath: String? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val content: String? = null,
    val output: String? = null,
    val error: String? = null,
    val exitCode: Int? = null,
    val durationLabel: String? = null,
    val patch: String? = null,
    val oldString: String? = null,
    val newString: String? = null,
)

internal object ToolResultInspector {
    fun fromTool(tool: MessagePart.Tool, fallbackTitle: String): ToolResultPresentation? =
        classify(
            ToolResultRequest(
                toolName = tool.toolName,
                fallbackTitle = fallbackTitle,
                command = jsonString(tool.input, "command"),
                path = jsonString(tool.input, "filePath", "file", "path"),
                pattern = jsonString(tool.input, "pattern"),
                searchPath = jsonString(tool.input, "path", "directory"),
                offset = jsonInt(tool.input, "offset"),
                limit = jsonInt(tool.input, "limit"),
                content = jsonString(tool.input, "content"),
                output = tool.output?.takeIf { it.isNotBlank() },
                error = tool.error?.takeIf { it.isNotBlank() },
                exitCode = jsonInt(tool.metadata, "exit"),
                patch = patchOf(tool),
                oldString = jsonString(tool.input, "oldString"),
                newString = jsonString(tool.input, "newString"),
            ),
        )

    fun thought(text: String, durationLabel: String?): ToolResultPresentation.Thought? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val (callout, rest) = splitThought(trimmed)
        return ToolResultPresentation.Thought(
            durationLabel = durationLabel?.takeIf { it.isNotEmpty() },
            callout = callout,
            paragraphs = rest,
            raw = trimmed,
        )
    }

    fun classify(request: ToolResultRequest): ToolResultPresentation? {
        val name = request.toolName.lowercase()
        val output = request.output?.takeIf { it.isNotBlank() }
        val error = request.error?.takeIf { it.isNotBlank() }
        val exit = request.exitCode ?: error?.let(::exitCodeIn) ?: output?.let(::exitCodeIn)
        val command = request.command?.takeIf { it.isNotBlank() }

        pullRequest(request, output, command, exit)?.let { return it }

        if (isShell(name) && output != null) {
            parseFileStatus(output)?.let { files ->
                return ToolResultPresentation.FileStatus(
                    command = command,
                    exitCode = exit,
                    durationLabel = request.durationLabel,
                    files = files,
                    counts = fileCounts(files),
                    raw = output,
                )
            }
        }

        if (output != null && isPathList(name, command, output)) {
            val rows = pathRows(output)
            if (rows.isNotEmpty()) {
                return ToolResultPresentation.PathList(
                    eyebrow = if (name == "glob") "Glob" else "Shell",
                    command = command ?: request.pattern,
                    paths = rows,
                    raw = output,
                )
            }
        }

        if (name == "read" && output != null) {
            val path = request.path?.takeIf { it.isNotBlank() } ?: "File"
            return source(ToolResultPresentation.SourceKind.Read, path, output, request)
        }
        if (name == "write") {
            val body = request.content?.takeIf { it.isNotEmpty() } ?: output
            val path = request.path?.takeIf { it.isNotBlank() }
            if (body != null && path != null) {
                return source(ToolResultPresentation.SourceKind.Write, path, body, request)
            }
        }

        if (name == "grep") {
            val pattern = request.pattern?.takeIf { it.isNotBlank() }
            val groups = if (output != null && pattern != null) parseGrep(output, request.path) else null
            if (pattern != null && groups != null) {
                return ToolResultPresentation.Grep(
                    pattern = pattern,
                    searchPath = request.searchPath,
                    matchCount = groups.sumOf { it.hits.size },
                    groups = groups,
                    raw = output.orEmpty(),
                )
            }
        }

        if (name == "edit" || name == "apply_patch" || name == "patch") {
            val path = request.path?.takeIf { it.isNotBlank() } ?: "File"
            val patch = request.patch?.takeIf { it.contains("@@") } ?: syntheticPatch(path, request.oldString, request.newString)
            if (patch != null) {
                val additions = request.patch?.let { countPrefix(it, '+') } ?: request.newString?.let { lineCount(it) } ?: 0
                val deletions = request.patch?.let { countPrefix(it, '-') } ?: request.oldString?.let { lineCount(it) } ?: 0
                return ToolResultPresentation.Edit(
                    eyebrow = if (name == "edit") "Edit" else "Patch",
                    filename = fileName(path),
                    additions = additions,
                    deletions = deletions,
                    patch = patch,
                    raw = patch,
                )
            }
        }

        val raw = output ?: error ?: return null
        return ToolResultPresentation.Unrecognized(
            title = request.fallbackTitle.ifBlank { toolLabel(name) },
            error = error?.takeIf { output != null && error != output },
            raw = raw,
        )
    }

    private fun pullRequest(
        request: ToolResultRequest,
        output: String?,
        command: String?,
        exit: Int?,
    ): ToolResultPresentation.PullRequest? {
        if (!isShell(request.toolName.lowercase()) || output == null) return null
        val match = prUrl.find(output) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: return null
        val haystack = listOfNotNull(output, command).joinToString("\n")
        return ToolResultPresentation.PullRequest(
            command = command,
            exitCode = exit,
            durationLabel = request.durationLabel,
            number = number,
            url = match.value,
            branch = branchIn(haystack),
            pullTitle = titleFlag(command),
            raw = output,
        )
    }

    private fun source(
        kind: ToolResultPresentation.SourceKind,
        path: String,
        body: String,
        request: ToolResultRequest,
    ): ToolResultPresentation.Source {
        val numbered = numberedLines(body)
        val lines =
            numbered ?: body.lines().let { split ->
                val trimmed = if (body.endsWith("\n") && split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
                val start = request.offset ?: 1
                trimmed.mapIndexed { index, line -> ToolResultPresentation.SourceLine(start + index, line) }
            }
        val end = lines.lastOrNull()?.number
        val range =
            when {
                request.offset != null && request.limit != null -> "lines ${request.offset}–${request.offset + request.limit - 1}"
                request.offset != null && end != null -> "lines ${request.offset}–$end"
                request.limit != null -> "first ${request.limit} lines"
                else -> null
            }
        return ToolResultPresentation.Source(
            kind = kind,
            filename = fileName(path),
            language = languageName(path),
            highlightLanguage = highlightLanguage(path),
            rangeLabel = range,
            lines = lines,
            raw = body,
        )
    }

    private fun parseFileStatus(output: String): List<ToolResultPresentation.FileRow>? {
        val lines = nonEmptyLines(output)
        if (lines.isEmpty()) return null
        val parsed = lines.map { statusLine.matchEntire(it) ?: return null }
        return parsed.map { match ->
            val xy = match.groupValues[1]
            val rawPath = renameDestination(match.groupValues[2])
            val path = unquote(rawPath)
            val letter = displayLetter(xy)
            ToolResultPresentation.FileRow(
                letter = letter.toString(),
                tone = tone(letter),
                name = fileName(path),
                directory = parentPath(path),
                fullPath = path,
            )
        }
    }

    private fun isPathList(name: String, command: String?, output: String): Boolean {
        val lines = nonEmptyLines(output)
        if (lines.isEmpty() || lines.any { !isPathLine(it) }) return false
        return name == "glob" || isFindCommand(command) || isShell(name)
    }

    private fun pathRows(output: String): List<ToolResultPresentation.PathRow> =
        nonEmptyLines(output).map { line ->
            val path = unquote(line.trim())
            ToolResultPresentation.PathRow(name = fileName(path), directory = parentPath(path), fullPath = path)
        }

    private fun parseGrep(output: String, fallbackFile: String?): List<ToolResultPresentation.GrepFile>? {
        val colon = nonEmptyLines(output).mapNotNull { grepColon.matchEntire(it) }
        if (colon.size == nonEmptyLines(output).size && colon.isNotEmpty()) {
            return colon
                .groupBy { unquote(it.groupValues[1]) }
                .map { (path, hits) ->
                    ToolResultPresentation.GrepFile(
                        name = fileName(path),
                        directory = parentPath(path),
                        hits = hits.map { ToolResultPresentation.GrepHit(it.groupValues[2].toInt(), it.groupValues[3]) },
                    )
                }
        }
        val grouped = parseGrepGroups(output, fallbackFile)
        return grouped?.takeIf { it.isNotEmpty() && it.sumOf { file -> file.hits.size } > 0 }
    }

    private fun parseGrepGroups(output: String, fallbackFile: String?): List<ToolResultPresentation.GrepFile>? {
        val files = mutableListOf<ToolResultPresentation.GrepFile>()
        var currentPath = fallbackFile
        var hits = mutableListOf<ToolResultPresentation.GrepHit>()
        fun flush() {
            val path = currentPath ?: return
            if (hits.isEmpty()) return
            files += ToolResultPresentation.GrepFile(fileName(path), parentPath(path), hits.toList())
            hits = mutableListOf()
        }
        for (line in output.lines()) {
            if (line.isBlank()) continue
            val hit = grepHit.matchEntire(line.trim())
            when {
                hit != null -> hits += ToolResultPresentation.GrepHit(hit.groupValues[1].toInt(), hit.groupValues[2])
                isPathLine(line) -> {
                    flush()
                    currentPath = unquote(line.trim())
                }
                else -> return null
            }
        }
        flush()
        return files.takeIf { it.isNotEmpty() }
    }

    private fun syntheticPatch(path: String, old: String?, new: String?): String? {
        if (old == null && new == null) return null
        val oldLines = splitLines(old.orEmpty())
        val newLines = splitLines(new.orEmpty())
        if (oldLines == newLines) return null
        val oldCount = oldLines.size
        val newCount = newLines.size
        return buildString {
            appendLine("--- a/$path")
            appendLine("+++ b/$path")
            appendLine("@@ -1,$oldCount +1,$newCount @@")
            oldLines.forEach { appendLine("-$it") }
            newLines.forEach { appendLine("+$it") }
        }
    }

    private fun fileCounts(files: List<ToolResultPresentation.FileRow>): List<ToolResultPresentation.Count> {
        val modified = files.count { it.tone == ToolResultPresentation.StatusTone.Modified }
        val added = files.count { it.tone == ToolResultPresentation.StatusTone.Added }
        val deleted = files.count { it.tone == ToolResultPresentation.StatusTone.Deleted }
        return listOfNotNull(
            modified.takeIf { it > 0 }?.let { ToolResultPresentation.Count("$it modified", ToolResultPresentation.StatusTone.Modified) },
            added.takeIf { it > 0 }?.let { ToolResultPresentation.Count("$it added", ToolResultPresentation.StatusTone.Added) },
            deleted.takeIf { it > 0 }?.let { ToolResultPresentation.Count("$it deleted", ToolResultPresentation.StatusTone.Deleted) },
        )
    }

    private fun displayLetter(xy: String): Char {
        val index = xy[0]
        val workTree = xy[1]
        return when {
            index != ' ' && workTree != ' ' && index != workTree -> workTree
            workTree != ' ' -> workTree
            else -> index
        }
    }

    private fun tone(letter: Char): ToolResultPresentation.StatusTone =
        when (letter) {
            'M' -> ToolResultPresentation.StatusTone.Modified
            'A' -> ToolResultPresentation.StatusTone.Added
            'D' -> ToolResultPresentation.StatusTone.Deleted
            else -> ToolResultPresentation.StatusTone.Other
        }

    private fun renameDestination(path: String): String {
        val arrow = " -> "
        val index = path.lastIndexOf(arrow)
        return if (index >= 0) path.substring(index + arrow.length) else path
    }

    internal fun unquote(path: String): String {
        val trimmed = path.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return trimmed
    }

    internal fun fileName(path: String): String {
        val normalized = path.trimEnd('/', '\\')
        val slash = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
        return if (slash >= 0) normalized.substring(slash + 1) else normalized
    }

    internal fun parentPath(path: String): String? {
        val normalized = path.trimEnd('/', '\\')
        val slash = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
        if (slash <= 0) return null
        return normalized.substring(0, slash)
    }

    private fun isPathLine(line: String): Boolean {
        val text = unquote(line.trim())
        if (text.isEmpty() || text.length > 2048 || text.contains('\t')) return false
        if (text.startsWith("http://") || text.startsWith("https://")) return false
        if (statusLine.matches(line.trim())) return false
        if (text.contains('/') || text.contains('\\') || text.startsWith("~") || text.startsWith("./")) return true
        return fileNamePattern.matches(text)
    }

    private fun isFindCommand(command: String?): Boolean {
        val trimmed = command?.trim()?.lowercase() ?: return false
        return trimmed == "find" || trimmed.startsWith("find ")
    }

    private fun isShell(name: String) = name == "bash" || name == "shell"

    private fun numberedLines(body: String): List<ToolResultPresentation.SourceLine>? {
        val lines = nonEmptyLines(body)
        if (lines.isEmpty()) return null
        val parsed = lines.map { numbered.matchEntire(it) ?: return null }
        return parsed.map { ToolResultPresentation.SourceLine(it.groupValues[1].toInt(), it.groupValues[2]) }
    }

    private fun splitThought(text: String): Pair<String, List<String>> {
        val match = sentenceEnd.find(text)
        if (match == null) return text to emptyList()
        val callout = text.substring(0, match.range.last + 1).trim()
        val rest = text.substring(match.range.last + 1).trim()
        if (rest.isEmpty()) return callout to emptyList()
        return callout to rest.split(paragraphBreak).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun exitCodeIn(text: String): Int? = exitCode.find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun branchIn(text: String): String? =
        branchQuoted.find(text)?.groupValues?.get(1)
            ?: headArrow.find(text)?.groupValues?.get(1)

    private fun titleFlag(command: String?): String? {
        val match = titleFlag.find(command ?: return null) ?: return null
        return unquote(match.groupValues[1])
    }

    private fun languageName(path: String): String? =
        when (extension(path)) {
            "kt", "kts" -> "Kotlin"
            "swift" -> "Swift"
            "java" -> "Java"
            "ts", "tsx" -> "TypeScript"
            "js", "jsx" -> "JavaScript"
            "py" -> "Python"
            "md" -> "Markdown"
            "json" -> "JSON"
            "xml" -> "XML"
            "sh", "bash", "zsh" -> "Shell"
            else -> null
        }

    private fun highlightLanguage(path: String): String? =
        when (extension(path)) {
            "kt", "kts" -> "kotlin"
            "swift" -> "swift"
            "java" -> "java"
            "ts", "tsx" -> "typescript"
            "js", "jsx" -> "javascript"
            "py" -> "python"
            "md" -> "markdown"
            "json" -> "json"
            "xml" -> "xml"
            "sh", "bash", "zsh" -> "shell"
            else -> null
        }

    private fun extension(path: String): String = fileName(path).substringAfterLast('.', "").lowercase()

    private fun countPrefix(patch: String, prefix: Char): Int =
        patch.lines().count { line ->
            line.startsWith(prefix) && !line.startsWith("---") && !line.startsWith("+++")
        }

    private fun lineCount(text: String): Int = splitLines(text).size

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun nonEmptyLines(text: String) = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    private fun jsonString(obj: JsonObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return null
    }

    private fun jsonInt(obj: JsonObject?, key: String): Int? = (obj?.get(key) as? JsonPrimitive)?.intOrNull

    private fun patchOf(tool: MessagePart.Tool): String? {
        val metadata = tool.metadata
        jsonString(metadata?.get("filediff") as? JsonObject, "patch")?.let { return it }
        val files = metadata?.get("files") as? JsonArray
        files?.forEach { element ->
            jsonString(element as? JsonObject, "patch")?.let { return it }
        }
        return tool.output?.takeIf { it.contains("@@") }
    }

    private fun toolLabel(name: String): String =
        when (name) {
            "bash", "shell" -> "Shell"
            "grep" -> "Grep"
            "glob" -> "Glob"
            "read" -> "Read"
            "write" -> "Write"
            "edit" -> "Edit"
            else -> name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    private val statusLine = Regex("""^([ MADRC?!]{2}) (.+)$""")
    private val prUrl = Regex("""https?://github\.com/[^/\s]+/[^/\s]+/pull/(\d+)""")
    private val branchQuoted = Regex("""branch '([^']+)'""")
    private val headArrow = Regex("""HEAD -> (\S+)""")
    private val titleFlag = Regex("""(?:--title|-t)\s+("[^"]+"|\S+)""")
    private val grepColon = Regex("""^(.+):(\d+):(.*)$""")
    private val grepHit = Regex("""^(\d+):\s?(.*)$""")
    private val numbered = Regex("""^(\d+):\s?(.*)$""")
    private val sentenceEnd = Regex("""[.!?](?=\s|$)""")
    private val paragraphBreak = Regex("""\n\s*\n""")
    private val exitCode = Regex("""(?i)(?:exited with code|exit)\s+(\d+)""")
    private val fileNamePattern = Regex("""^[^/\s]+\.[A-Za-z0-9]{1,8}$""")
}
