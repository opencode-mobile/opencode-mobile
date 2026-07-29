package dev.opencode.mobile.markdown

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

@Serializable
data class MarkdownParseRequest(
    val markdown: String,
    val variant: String = "standalone",
    val isStreaming: Boolean = false,
    /** When false, prefer the semantic-fallback highlighter over TextMate. */
    val textMateEnabled: Boolean = false,
)

@Serializable
data class MarkdownDocument(
    val renderTreeVersion: Int = 1,
    val sourceHash: Int,
    val blocks: List<MarkdownBlock>,
    val diagnostics: List<MarkdownDiagnostic> = emptyList(),
)

@Serializable
data class MarkdownParseEnvelope(
    val document: MarkdownDocument,
    val textMateObservations: List<TextMateParseObservation> = emptyList(),
)

@Serializable
sealed interface MarkdownBlock {
    @Serializable
    @SerialName("paragraph")
    data class Paragraph(
        val inlines: List<MarkdownInline>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("heading")
    data class Heading(
        val level: Int,
        val inlines: List<MarkdownInline>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("blockquote")
    data class BlockQuote(
        val inlines: List<MarkdownInline>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("bulletList")
    data class BulletList(
        val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("orderedList")
    data class OrderedList(
        val start: Int,
        val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("codeBlock")
    data class CodeBlock(
        val language: String?,
        val code: String,
        val lines: List<String>,
        val spans: List<CodeSpan>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("diffBlock")
    data class DiffBlock(
        val language: String?,
        val lines: List<DiffLine>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("table")
    data class Table(
        val header: MarkdownTableRow,
        val rows: List<MarkdownTableRow>,
    ) : MarkdownBlock

    @Serializable
    @SerialName("thematicBreak")
    data object ThematicBreak : MarkdownBlock
}

@Serializable
data class MarkdownListItem(
    val inlines: List<MarkdownInline>,
)

@Serializable
data class MarkdownTableRow(
    val cells: List<MarkdownTableCell>,
)

@Serializable
data class MarkdownTableCell(
    val inlines: List<MarkdownInline>,
)

@Serializable
sealed interface MarkdownInline {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : MarkdownInline

    @Serializable
    @SerialName("emphasis")
    data class Emphasis(
        val children: List<MarkdownInline>,
    ) : MarkdownInline

    @Serializable
    @SerialName("strong")
    data class Strong(
        val children: List<MarkdownInline>,
    ) : MarkdownInline

    @Serializable
    @SerialName("code")
    data class Code(
        val code: String,
    ) : MarkdownInline

    @Serializable
    @SerialName("link")
    data class Link(
        val destination: String,
        val children: List<MarkdownInline>,
    ) : MarkdownInline

    @Serializable
    @SerialName("imageAlt")
    data class ImageAlt(
        val alt: String,
    ) : MarkdownInline

    @Serializable
    @SerialName("softBreak")
    data object SoftBreak : MarkdownInline

    @Serializable
    @SerialName("hardBreak")
    data object HardBreak : MarkdownInline
}

@Serializable
data class CodeSpan(
    val start: Int,
    val end: Int,
    val role: CodeTokenRole,
)

@Serializable
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val spans: List<CodeSpan>,
)

@Serializable
enum class DiffLineKind {
    Context,
    Added,
    Deleted,
    Header,
}

@Serializable
enum class CodeTokenRole {
    Plain,
    Comment,
    Keyword,
    String,
    Number,
    Type,
    Function,
    Property,
    Variable,
    Constant,
    Operator,
    Punctuation,
    Tag,
    Attribute,
    Inserted,
    Deleted,
    Warning,
    Critical,
}

@Serializable
data class MarkdownDiagnostic(
    val kind: MarkdownDiagnosticKind,
    val message: String,
)

@Serializable
enum class MarkdownDiagnosticKind {
    RawHtmlLiteral,
    ImageOmitted,
    MalformedTable,
    UnsupportedLanguage,
    HighlighterFallback,
    HighlighterBackendFallback,
}

object OpenCodeMarkdownJson {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

    fun parseMarkdownJson(requestJson: String): String {
        val request = json.decodeFromString<MarkdownParseRequest>(requestJson)
        val codeHighlighter = codeHighlighterFor(request)
        return json.encodeToString(
            OpenCodeMarkdownParser.parse(
                markdown = request.markdown,
                codeHighlighter = codeHighlighter,
            ),
        )
    }

    fun parseMarkdownObservedJson(requestJson: String): String {
        val request = json.decodeFromString<MarkdownParseRequest>(requestJson)
        return json.encodeToString(
            OpenCodeMarkdownParser.parseObserved(
                markdown = request.markdown,
                codeHighlighter = codeHighlighterFor(request),
                observeTextMate = request.textMateEnabled && !request.isStreaming,
            ),
        )
    }

    private fun codeHighlighterFor(request: MarkdownParseRequest): OpenCodeCodeHighlighter =
        if (request.isStreaming || !request.textMateEnabled) {
            OpenCodeSemanticFallbackHighlighter
        } else {
            OpenCodeMarkdownHighlighterProvider.default
        }
}

object OpenCodeMarkdownDetector {
    private val blockPattern =
        Regex(
            pattern =
                """(?m)(^#{1,6}\s)|(^\s*[-*+]\s+)|(^\s*\d+[.)]\s+)|(^>\s)|(^```)|(^~~~)|(^\s*\|.+\|\s*$)|(^\s{4,}\S)|(^\s*(-{3,}|\*{3,}|_{3,})\s*$)""",
        )
    private val inlinePattern =
        Regex(
            pattern =
                """(`[^`]+`)|(!?\[[^]]+]\([^)]+\))|(\*\*[^*\n]+\*\*)|(__[^_\n]+__)|((^|[^\*])\*[^*\n]+\*($|[^\*]))|((^|[^_])_[^_\n]+_($|[^_]))""",
        )

    fun shouldRender(text: String): Boolean = blockPattern.containsMatchIn(text) || inlinePattern.containsMatchIn(text)
}

fun openCodeCodeHighlighterForParse(
    preferred: OpenCodeCodeHighlighter,
    isStreaming: Boolean,
    textMateEnabled: Boolean = false,
): OpenCodeCodeHighlighter =
    if (isStreaming || !textMateEnabled) {
        OpenCodeSemanticFallbackHighlighter
    } else {
        preferred
    }

object OpenCodeMarkdownParser {
    fun parse(
        markdown: String,
        codeHighlighter: OpenCodeCodeHighlighter = OpenCodeSyntaxHighlighter.default,
        isStreaming: Boolean = false,
        textMateEnabled: Boolean = false,
    ): MarkdownDocument =
        parse(
            markdown = markdown,
            codeHighlighter =
                openCodeCodeHighlighterForParse(
                    preferred = codeHighlighter,
                    isStreaming = isStreaming,
                    textMateEnabled = textMateEnabled,
                ),
        )

    fun parse(
        markdown: String,
        codeHighlighter: OpenCodeCodeHighlighter,
    ): MarkdownDocument =
        parseInternal(
            markdown = markdown,
            codeHighlighter = codeHighlighter,
            observeTextMate = false,
        ).document

    fun parseObserved(
        markdown: String,
        codeHighlighter: OpenCodeCodeHighlighter = OpenCodeSyntaxHighlighter.default,
        isStreaming: Boolean = false,
        textMateEnabled: Boolean = false,
    ): MarkdownParseEnvelope =
        parseObserved(
            markdown = markdown,
            codeHighlighter =
                openCodeCodeHighlighterForParse(
                    preferred = codeHighlighter,
                    isStreaming = isStreaming,
                    textMateEnabled = textMateEnabled,
                ),
            observeTextMate = textMateEnabled && !isStreaming,
        )

    fun parseObserved(
        markdown: String,
        codeHighlighter: OpenCodeCodeHighlighter,
        observeTextMate: Boolean,
    ): MarkdownParseEnvelope =
        parseInternal(
            markdown = markdown,
            codeHighlighter = codeHighlighter,
            observeTextMate = observeTextMate,
        )

    private fun parseInternal(
        markdown: String,
        codeHighlighter: OpenCodeCodeHighlighter,
        observeTextMate: Boolean,
    ): MarkdownParseEnvelope {
        if (markdown.isBlank()) {
            return MarkdownParseEnvelope(
                document = MarkdownDocument(sourceHash = stableMarkdownSourceHash(markdown), blocks = emptyList()),
            )
        }

        val diagnostics = mutableListOf<MarkdownDiagnostic>()
        val observations = mutableListOf<TextMateParseObservation>()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        var fenceCount = 0

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index += 1
                continue
            }

            val fenced =
                parseFence(
                    lines = lines,
                    start = index,
                    codeHighlighter = codeHighlighter,
                    messageCharacters = markdown.length,
                    fenceIndex = fenceCount + 1,
                    observeTextMate = observeTextMate,
                )
            if (fenced != null) {
                blocks += fenced.block
                diagnostics += fenced.diagnostics
                fenced.observation?.let(observations::add)
                fenceCount += 1
                index = fenced.nextIndex
                continue
            }

            val table = parseTable(lines, index, diagnostics)
            if (table != null) {
                blocks += table.block
                index = table.nextIndex
                continue
            }

            val heading = headingRegex.find(line)
            if (heading != null) {
                val level = heading.groupValues[1].length.coerceIn(1, 6)
                val content = heading.groupValues[2].trim()
                val parsed = parseInlines(content)
                blocks += MarkdownBlock.Heading(level = level, inlines = parsed.inlines)
                diagnostics += parsed.diagnostics
                index += 1
                continue
            }

            if (thematicBreakRegex.matches(line.trim())) {
                blocks += MarkdownBlock.ThematicBreak
                index += 1
                continue
            }

            if (line.trimStart().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quoteLines += lines[index].trimStart().removePrefix(">").trimStart()
                    index += 1
                }
                val parsed = parseInlines(quoteLines.joinToString("\n"))
                blocks += MarkdownBlock.BlockQuote(parsed.inlines)
                diagnostics += parsed.diagnostics
                continue
            }

            val unordered = unorderedRegex.find(line)
            if (unordered != null) {
                val items = mutableListOf<MarkdownListItem>()
                while (index < lines.size) {
                    val match = unorderedRegex.find(lines[index]) ?: break
                    val parsed = parseInlines(match.groupValues[1].trim())
                    items += MarkdownListItem(parsed.inlines)
                    diagnostics += parsed.diagnostics
                    index += 1
                }
                blocks += MarkdownBlock.BulletList(items)
                continue
            }

            val ordered = orderedRegex.find(line)
            if (ordered != null) {
                val items = mutableListOf<MarkdownListItem>()
                val start = ordered.groupValues[1].toIntOrNull() ?: 1
                while (index < lines.size) {
                    val match = orderedRegex.find(lines[index]) ?: break
                    val parsed = parseInlines(match.groupValues[2].trim())
                    items += MarkdownListItem(parsed.inlines)
                    diagnostics += parsed.diagnostics
                    index += 1
                }
                blocks += MarkdownBlock.OrderedList(start = start, items = items)
                continue
            }

            val paragraphLines = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines, index)) {
                val paragraphLine = lines[index]
                if (rawHtmlRegex.containsMatchIn(paragraphLine)) {
                    diagnostics +=
                        MarkdownDiagnostic(
                            MarkdownDiagnosticKind.RawHtmlLiteral,
                            "Raw HTML was rendered as literal text.",
                        )
                }
                paragraphLines += paragraphLine
                index += 1
            }
            val parsed = parseInlines(paragraphLines.joinToString("\n"))
            blocks += MarkdownBlock.Paragraph(parsed.inlines)
            diagnostics += parsed.diagnostics
        }

        return MarkdownParseEnvelope(
            document =
                MarkdownDocument(
                    sourceHash = stableMarkdownSourceHash(markdown),
                    blocks = blocks,
                    diagnostics = diagnostics.distinct(),
                ),
            textMateObservations = observations.map { observation -> observation.copy(fenceCount = fenceCount) },
        )
    }

    private fun startsBlock(
        lines: List<String>,
        index: Int,
    ): Boolean {
        val line = lines[index]
        return line.isBlank() ||
            fenceRegex.containsMatchIn(line) ||
            headingRegex.containsMatchIn(line) ||
            thematicBreakRegex.matches(line.trim()) ||
            line.trimStart().startsWith(">") ||
            unorderedRegex.containsMatchIn(line) ||
            orderedRegex.containsMatchIn(line) ||
            isTableStart(lines, index)
    }

    private fun parseFence(
        lines: List<String>,
        start: Int,
        codeHighlighter: OpenCodeCodeHighlighter,
        messageCharacters: Int,
        fenceIndex: Int,
        observeTextMate: Boolean,
    ): ParseResult? {
        val match = fenceRegex.find(lines[start]) ?: return null
        val marker = match.groupValues[1]
        val language = match.groupValues[2].trim().takeIf { it.isNotBlank() }?.substringBefore(' ')
        val normalizedLanguage = language?.lowercase()
        val content = mutableListOf<String>()
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.closesFence(marker)) {
                break
            }
            content += line
            index += 1
        }
        val nextIndex = if (index < lines.size) index + 1 else index
        val code = content.joinToString("\n")
        val metrics = if (observeTextMate) OpenCodeTextMateMetricsAccumulator() else null
        val highlightStartedAt = metrics?.let { TimeSource.Monotonic.markNow() }
        val syntaxHighlight =
            if (metrics == null) {
                codeHighlighter.highlight(normalizedLanguage, code)
            } else {
                codeHighlighter.highlight(normalizedLanguage, code, metrics)
            }
        val totalNanoseconds = highlightStartedAt?.elapsedNow()?.inWholeNanoseconds ?: 0
        val diagnostics = mutableListOf<MarkdownDiagnostic>()
        if (normalizedLanguage != null && !codeHighlighter.supportsLanguage(normalizedLanguage)) {
            diagnostics +=
                MarkdownDiagnostic(
                    MarkdownDiagnosticKind.UnsupportedLanguage,
                    "No bundled grammar is registered for '$normalizedLanguage'.",
                )
        }
        diagnostics += syntaxHighlight.diagnostics

        val block =
            if (normalizedLanguage == "diff" || normalizedLanguage == "patch") {
                MarkdownBlock.DiffBlock(
                    language = normalizedLanguage,
                    lines = content.map { line -> parseDiffLine(line) },
                )
            } else {
                MarkdownBlock.CodeBlock(
                    language = normalizedLanguage,
                    code = code,
                    lines = content,
                    spans = syntaxHighlight.spans,
                )
            }
        val observation =
            metrics?.let {
                TextMateParseObservation(
                    requestedLanguage = normalizedLanguage?.take(MAX_LOGBOOK_LANGUAGE_LENGTH),
                    canonicalLanguage = syntaxHighlight.canonicalLanguage?.take(MAX_LOGBOOK_LANGUAGE_LENGTH),
                    outcome =
                        syntaxHighlight.textMateOutcome
                            ?: if (syntaxHighlight.source == SyntaxHighlightSource.TextMate) {
                                TextMateParseOutcome.Highlighted
                            } else {
                                TextMateParseOutcome.Fallback
                            },
                    messageCharacters = messageCharacters,
                    fenceCharacters = code.length,
                    fenceLines = content.size,
                    fenceIndex = fenceIndex,
                    fenceCount = 0,
                    spanCount = syntaxHighlight.spans.size,
                    backend = it.backendName(),
                    totalNanoseconds = totalNanoseconds,
                    stages = it.stageSummaries(),
                    diagnosticKinds = diagnostics.map(MarkdownDiagnostic::kind).distinct(),
                )
            }
        return ParseResult(
            block = block,
            nextIndex = nextIndex,
            diagnostics = diagnostics,
            observation = observation,
        )
    }

    private fun parseDiffLine(line: String): DiffLine {
        val kind =
            when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> DiffLineKind.Header
                line.startsWith("+") -> DiffLineKind.Added
                line.startsWith("-") -> DiffLineKind.Deleted
                else -> DiffLineKind.Context
            }
        val role =
            when (kind) {
                DiffLineKind.Added -> CodeTokenRole.Inserted
                DiffLineKind.Deleted -> CodeTokenRole.Deleted
                DiffLineKind.Header -> CodeTokenRole.Warning
                DiffLineKind.Context -> CodeTokenRole.Plain
            }
        return DiffLine(kind = kind, text = line, spans = listOf(CodeSpan(0, line.length, role)))
    }

    private fun parseTable(
        lines: List<String>,
        start: Int,
        diagnostics: MutableList<MarkdownDiagnostic>,
    ): TableResult? {
        if (!isTableStart(lines, start)) return null
        val headerCells = splitTableRow(lines[start])
        val rows = mutableListOf<MarkdownTableRow>()
        var index = start + 2
        while (index < lines.size && lines[index].contains("|") && lines[index].isNotBlank()) {
            rows += parseTableRow(lines[index], diagnostics)
            index += 1
        }
        return TableResult(
            block =
                MarkdownBlock.Table(
                    header = MarkdownTableRow(headerCells.map { MarkdownTableCell(parseInlines(it).inlines) }),
                    rows = rows,
                ),
            nextIndex = index,
        )
    }

    private fun parseTableRow(
        line: String,
        diagnostics: MutableList<MarkdownDiagnostic>,
    ): MarkdownTableRow {
        val cells =
            splitTableRow(line).map { cell ->
                val parsed = parseInlines(cell)
                diagnostics += parsed.diagnostics
                MarkdownTableCell(parsed.inlines)
            }
        return MarkdownTableRow(cells)
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    private fun isTableStart(
        lines: List<String>,
        index: Int,
    ): Boolean {
        if (index + 1 >= lines.size) return false
        return lines[index].contains("|") && tableSeparatorRegex.matches(lines[index + 1].trim())
    }

    private fun parseInlines(text: String): InlineResult {
        val diagnostics = mutableListOf<MarkdownDiagnostic>()
        val inlines = mutableListOf<MarkdownInline>()
        val buffer = StringBuilder()
        var index = 0

        fun flushText() {
            if (buffer.isNotEmpty()) {
                inlines += MarkdownInline.Text(buffer.toString())
                buffer.clear()
            }
        }

        while (index < text.length) {
            when {
                text.startsWith("  \n", index) -> {
                    flushText()
                    inlines += MarkdownInline.HardBreak
                    index += 3
                }
                text[index] == '\n' -> {
                    flushText()
                    inlines += MarkdownInline.SoftBreak
                    index += 1
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index) {
                        flushText()
                        inlines += MarkdownInline.Code(text.substring(index + 1, end))
                        index = end + 1
                    } else {
                        buffer.append(text[index])
                        index += 1
                    }
                }
                text.startsWith("![", index) -> {
                    val closeLabel = text.indexOf("](", index + 2)
                    val closeUrl = if (closeLabel >= 0) text.indexOf(')', closeLabel + 2) else -1
                    if (closeLabel >= 0 && closeUrl >= 0) {
                        flushText()
                        inlines += MarkdownInline.ImageAlt(text.substring(index + 2, closeLabel))
                        diagnostics += MarkdownDiagnostic(MarkdownDiagnosticKind.ImageOmitted, "Markdown image was not loaded.")
                        index = closeUrl + 1
                    } else {
                        buffer.append(text[index])
                        index += 1
                    }
                }
                text[index] == '[' -> {
                    val closeLabel = text.indexOf("](", index + 1)
                    val closeUrl = if (closeLabel >= 0) text.indexOf(')', closeLabel + 2) else -1
                    if (closeLabel >= 0 && closeUrl >= 0) {
                        val destination = text.substring(closeLabel + 2, closeUrl)
                        flushText()
                        inlines +=
                            MarkdownInline.Link(
                                destination = destination,
                                children = parseInlines(text.substring(index + 1, closeLabel)).inlines,
                            )
                        index = closeUrl + 1
                    } else {
                        buffer.append(text[index])
                        index += 1
                    }
                }
                text.startsWith("**", index) || text.startsWith("__", index) -> {
                    val marker = text.substring(index, index + 2)
                    val end = text.indexOf(marker, index + 2)
                    if (end > index) {
                        flushText()
                        inlines += MarkdownInline.Strong(parseInlines(text.substring(index + 2, end)).inlines)
                        index = end + 2
                    } else {
                        buffer.append(text[index])
                        index += 1
                    }
                }
                text[index] == '*' || text[index] == '_' -> {
                    val marker = text[index]
                    val end = text.indexOf(marker, index + 1)
                    if (end > index + 1) {
                        flushText()
                        inlines += MarkdownInline.Emphasis(parseInlines(text.substring(index + 1, end)).inlines)
                        index = end + 1
                    } else {
                        buffer.append(text[index])
                        index += 1
                    }
                }
                else -> {
                    buffer.append(text[index])
                    index += 1
                }
            }
        }
        flushText()
        return InlineResult(inlines = inlines, diagnostics = diagnostics)
    }

    private data class InlineResult(
        val inlines: List<MarkdownInline>,
        val diagnostics: List<MarkdownDiagnostic> = emptyList(),
    )

    private data class ParseResult(
        val block: MarkdownBlock,
        val nextIndex: Int,
        val diagnostics: List<MarkdownDiagnostic> = emptyList(),
        val observation: TextMateParseObservation? = null,
    )

    private data class TableResult(
        val block: MarkdownBlock.Table,
        val nextIndex: Int,
    )

    private val headingRegex = Regex("""^\s*(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val unorderedRegex = Regex("""^\s*[-*+]\s+(.+)$""")
    private val orderedRegex = Regex("""^\s*(\d+)[.)]\s+(.+)$""")
    private val fenceRegex = Regex("""^\s*(```|~~~)\s*([^`]*)$""")
    private val thematicBreakRegex = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
    private val tableSeparatorRegex = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$""")
    private val rawHtmlRegex = Regex("""</?[A-Za-z][^>]*>""")
    private const val MAX_LOGBOOK_LANGUAGE_LENGTH = 64

    private fun String.closesFence(marker: String): Boolean {
        val trimmed = trim()
        return trimmed.length >= marker.length && trimmed.all { char -> char == marker.first() }
    }

}

fun MarkdownDocument.isLikelyExpandable(): Boolean {
    val visualLines =
        blocks.sumOf { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> block.lines.size + 1
                is MarkdownBlock.DiffBlock -> block.lines.size + 1
                is MarkdownBlock.Table -> block.rows.size + 2
                is MarkdownBlock.BulletList -> block.items.size
                is MarkdownBlock.OrderedList -> block.items.size
                is MarkdownBlock.BlockQuote -> 2
                is MarkdownBlock.Heading -> 2
                is MarkdownBlock.Paragraph -> 1
                MarkdownBlock.ThematicBreak -> 1
            }
        }
    return visualLines > 6 || blocks.any { it is MarkdownBlock.CodeBlock && it.code.length > 420 }
}

fun stableMarkdownSourceHash(markdown: String): Int {
    var hash = 0x811c9dc5.toInt()
    for (byte in markdown.encodeToByteArray()) {
        hash = hash xor byte.toInt()
        hash *= 0x01000193
    }
    return hash
}

fun plainTextFromMarkdownInlines(inlines: List<MarkdownInline>): String =
    buildString {
        appendPlainMarkdownInlines(inlines)
    }

private fun StringBuilder.appendPlainMarkdownInlines(inlines: List<MarkdownInline>) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)
            is MarkdownInline.Code -> append(inline.code)
            is MarkdownInline.Emphasis -> appendPlainMarkdownInlines(inline.children)
            is MarkdownInline.Strong -> appendPlainMarkdownInlines(inline.children)
            is MarkdownInline.Link -> {
                val start = length
                appendPlainMarkdownInlines(inline.children)
                if (length == start) append(inline.destination)
            }
            is MarkdownInline.ImageAlt -> append("[image: ${inline.alt}]")
            MarkdownInline.SoftBreak -> append('\n')
            MarkdownInline.HardBreak -> append('\n')
        }
    }
}
