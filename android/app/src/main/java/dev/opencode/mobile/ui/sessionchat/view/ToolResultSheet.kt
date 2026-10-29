package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.OpenCodeHighlightCoreCodeHighlighter
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.UnifiedDiffParser
import dev.opencode.mobile.ui.components.codeColors
import dev.opencode.mobile.ui.sessionchat.ToolResultPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ToolResultPinnedHeader(presentation: ToolResultPresentation) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        presentation.eyebrow?.let { eyebrow ->
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weaker"),
            )
        }
        when (presentation) {
            is ToolResultPresentation.FileStatus -> {
                CountLine(presentation.counts)
                CommandStrip(presentation.command, presentation.exitCode, presentation.durationLabel)
            }
            is ToolResultPresentation.PathList -> CommandStrip(presentation.command, null, null)
            is ToolResultPresentation.PullRequest ->
                CommandStrip(presentation.command, presentation.exitCode, presentation.durationLabel)
            is ToolResultPresentation.Thought ->
                presentation.durationLabel?.let { Subline(it) }
            is ToolResultPresentation.Source -> SourceSubline(presentation)
            is ToolResultPresentation.Grep ->
                Subline(
                    buildString {
                        append("${presentation.matchCount} matches · ${presentation.groups.size} files")
                        presentation.searchPath?.let { append(" · $it") }
                    },
                )
            is ToolResultPresentation.Edit -> DiffCounts(presentation.additions, presentation.deletions)
            is ToolResultPresentation.Unrecognized -> Unit
        }
    }
}

@Composable
internal fun ToolResultBody(presentation: ToolResultPresentation) {
    when (presentation) {
        is ToolResultPresentation.FileStatus ->
            Column {
                presentation.files.forEach { row ->
                    FileResultRow(
                        letter = row.letter,
                        tone = row.tone,
                        name = row.name,
                        directory = row.directory,
                        accessible = "${row.letter} ${row.name}, ${row.fullPath}",
                    )
                }
            }
        is ToolResultPresentation.PathList ->
            Column {
                presentation.paths.forEach { row ->
                    FileResultRow(
                        letter = null,
                        tone = null,
                        name = row.name,
                        directory = row.directory,
                        accessible = listOfNotNull(row.name, row.fullPath).joinToString(", "),
                    )
                }
            }
        is ToolResultPresentation.PullRequest -> PullRequestBody(presentation)
        is ToolResultPresentation.Thought -> ThoughtBody(presentation)
        is ToolResultPresentation.Source -> SourceBody(presentation)
        is ToolResultPresentation.Grep -> GrepBody(presentation)
        is ToolResultPresentation.Edit -> EditBody(presentation)
        is ToolResultPresentation.Unrecognized -> UnrecognizedBody(presentation)
    }
}

@Composable
private fun CountLine(counts: List<ToolResultPresentation.Count>) {
    if (counts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        counts.forEach { count ->
            Text(text = count.label, style = MaterialTheme.typography.bodySmall, color = toneColor(count.tone))
        }
    }
}

@Composable
private fun Subline(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = openCodeColor("text-base"))
}

@Composable
private fun CommandStrip(command: String?, exitCode: Int?, durationLabel: String?) {
    if (command.isNullOrBlank() && exitCode == null && durationLabel == null) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!command.isNullOrBlank()) {
            Text(text = command, style = CodeTextStyle, color = openCodeColor("text-base"), maxLines = 1, softWrap = false)
        }
        exitCode?.let { code ->
            Text(
                text = "exit $code",
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor(if (code == 0) "success" else "error"),
                maxLines = 1,
            )
        }
        durationLabel?.let { label ->
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = openCodeColor("text-weaker"), maxLines = 1)
        }
    }
}

@Composable
private fun FileResultRow(
    letter: String?,
    tone: ToolResultPresentation.StatusTone?,
    name: String,
    directory: String?,
    accessible: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics { contentDescription = accessible },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (letter != null) {
            Text(
                text = letter,
                modifier = Modifier.width(16.dp),
                style = CodeTextStyle,
                color = tone?.let { toneColor(it) } ?: openCodeColor("text-base"),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            directory?.let { dir ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        text = "\u202A$dir\u202C",
                        style = MaterialTheme.typography.bodySmall,
                        color = openCodeColor("text-weaker"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        textAlign = TextAlign.Left,
                    )
                }
            }
        }
    }
}

@Composable
private fun PullRequestBody(presentation: ToolResultPresentation.PullRequest) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "#${presentation.number}",
            style = MaterialTheme.typography.headlineSmall,
            color = openCodeColor("text-strong"),
        )
        presentation.branch?.let { branch ->
            Text(text = branch, style = MaterialTheme.typography.bodyMedium, color = openCodeColor("text-base"))
        }
        Text(
            text = presentation.url,
            modifier =
                Modifier
                    .clickable { clipboard.setText(AnnotatedString(presentation.url)) }
                    .semantics { contentDescription = "Copy ${presentation.url}" },
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("interactive"),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ThoughtBody(presentation: ToolResultPresentation.Thought) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier =
                    Modifier
                        .width(2.dp)
                        .height(22.dp)
                        .background(openCodeColor("interactive")),
            )
            Text(
                text = presentation.callout,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = openCodeColor("text-strong"),
            )
        }
        presentation.paragraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
            )
        }
    }
}

@Composable
private fun SourceSubline(presentation: ToolResultPresentation.Source) {
    val parts =
        listOfNotNull(
            presentation.language,
            "${presentation.lines.size} lines".takeIf { presentation.lines.isNotEmpty() },
            presentation.rangeLabel,
        )
    if (parts.isNotEmpty()) Subline(parts.joinToString(" · "))
}

@Composable
private fun SourceBody(presentation: ToolResultPresentation.Source) {
    val source = presentation.lines.joinToString("\n") { it.text }
    val spans by produceState(initialValue = emptyList<CodeSpan>(), source, presentation.highlightLanguage) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    OpenCodeHighlightCoreCodeHighlighter.highlight(presentation.highlightLanguage, source).spans
                }.getOrDefault(emptyList())
            }
    }
    val scroll = rememberScrollState()
    val colors = codeColors()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface(Color.Transparent), OpenCodeSmallShape)
                .horizontalScroll(scroll)
                .padding(vertical = if (colors.background != null) 8.dp else 0.dp),
    ) {
        presentation.lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = line.number.toString(),
                    modifier = Modifier.width(36.dp).padding(end = 8.dp),
                    style = CodeTextStyle,
                    color = openCodeColor("text-weaker"),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = highlighted(line.text, spansForLine(source, line, spans), colors),
                    color = colors.plain,
                    style = CodeTextStyle,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun GrepBody(presentation: ToolResultPresentation.Grep) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        presentation.groups.forEach { group ->
            Column {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = openCodeColor("text-strong"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                group.directory?.let { dir ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(
                            text = "\u202A$dir\u202C",
                            style = MaterialTheme.typography.bodySmall,
                            color = openCodeColor("text-weaker"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Left,
                        )
                    }
                }
                group.hits.forEach { hit ->
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            text = hit.line.toString(),
                            modifier = Modifier.width(36.dp).padding(end = 8.dp),
                            style = CodeTextStyle,
                            color = openCodeColor("text-weaker"),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = markPattern(hit.text, presentation.pattern, openCodeColor("warning").copy(alpha = 0.28f)),
                            style = CodeTextStyle,
                            color = openCodeColor("text-strong"),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditBody(presentation: ToolResultPresentation.Edit) {
    val document = UnifiedDiffParser.parse(presentation.patch)
    OpenCodeUnifiedDiff(
        file =
            FileDiff(
                path = presentation.filename,
                patch = presentation.patch,
                additions = presentation.additions,
                deletions = presentation.deletions,
                status = FileDiffStatus.Modified,
            ),
        document = document,
        contextMode = UnifiedDiffContextMode.Expanded,
    )
}

@Composable
private fun DiffCounts(additions: Int, deletions: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (additions > 0) {
            Text("+$additions", style = MaterialTheme.typography.bodySmall, color = openCodeColor("diff-add"))
        }
        if (deletions > 0) {
            Text("−$deletions", style = MaterialTheme.typography.bodySmall, color = openCodeColor("diff-delete"))
        }
    }
}

@Composable
private fun UnrecognizedBody(presentation: ToolResultPresentation.Unrecognized) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presentation.error?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodyMedium, color = openCodeColor("error"))
        }
        SelectionContainer {
            Text(
                text = presentation.raw,
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .wrapContentWidth(unbounded = true),
                style = CodeTextStyle,
                color = openCodeColor("text-strong"),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun toneColor(tone: ToolResultPresentation.StatusTone) =
    openCodeColor(
        when (tone) {
            ToolResultPresentation.StatusTone.Modified -> "warning"
            ToolResultPresentation.StatusTone.Added -> "diff-add"
            ToolResultPresentation.StatusTone.Deleted -> "diff-delete"
            ToolResultPresentation.StatusTone.Other -> "text-base"
        },
    )

private fun markPattern(
    text: String,
    pattern: String,
    mark: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (pattern.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val found = text.indexOf(pattern, startIndex = index, ignoreCase = true)
            if (found < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, found))
            withStyle(SpanStyle(background = mark)) {
                append(text.substring(found, found + pattern.length))
            }
            index = found + pattern.length
        }
    }
}

private fun spansForLine(
    source: String,
    line: ToolResultPresentation.SourceLine,
    spans: List<CodeSpan>,
): List<CodeSpan> {
    val start = source.indexOf(line.text)
    if (start < 0) return emptyList()
    val end = start + line.text.length
    return spans.mapNotNull { span ->
        val from = maxOf(span.start, start)
        val to = minOf(span.end, end)
        if (from < to) CodeSpan(from - start, to - start, span.role) else null
    }
}

private fun highlighted(
    text: String,
    spans: List<CodeSpan>,
    colors: dev.opencode.mobile.ui.components.CodeColors,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        spans.sortedBy { it.start }.forEach { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (cursor < start) append(text.substring(cursor, start))
            withStyle(SpanStyle(color = colors.colorFor(span.role))) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
