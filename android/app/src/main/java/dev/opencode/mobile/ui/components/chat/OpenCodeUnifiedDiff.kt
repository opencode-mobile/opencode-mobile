package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.theme.openCodeTextScaled

enum class UnifiedDiffRowKind { Hunk, Metadata, Context, Addition, Deletion }

enum class UnifiedDiffContextMode { Collapsed, Expanded }

data class UnifiedDiffRow(
    val id: Int,
    val kind: UnifiedDiffRowKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

data class UnifiedDiffHunk(
    val id: Int,
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val rows: List<UnifiedDiffRow>,
)

data class UnifiedDiffDocument(
    val rows: List<UnifiedDiffRow>,
    val hunks: List<UnifiedDiffHunk>,
) {
    // Derived once per document and kept with it; not part of equality.
    private val collapsedRows: List<UnifiedDiffRow> by lazy { UnifiedDiffVisibility.collapsedRows(rows, DEFAULT_CONTEXT_LINES) }
    private val widestExpandedRows: List<UnifiedDiffRow> by lazy { UnifiedDiffVisibility.widestRows(rows) }
    private val widestCollapsedRows: List<UnifiedDiffRow> by lazy { UnifiedDiffVisibility.widestRows(collapsedRows) }

    /** Rows shown in [contextMode]: collapsed keeps [contextLines] context rows on each side of a change. Linear, and cached for the default. */
    fun visibleRows(
        contextMode: UnifiedDiffContextMode,
        contextLines: Int = DEFAULT_CONTEXT_LINES,
    ): List<UnifiedDiffRow> =
        when {
            contextMode == UnifiedDiffContextMode.Expanded -> rows
            contextLines == DEFAULT_CONTEXT_LINES -> collapsedRows
            else -> UnifiedDiffVisibility.collapsedRows(rows, contextLines)
        }

    /** True when collapsing hides some context, so expanding shows more. */
    val canExpandContext: Boolean get() = collapsedRows.size < rows.size

    /** The few rows likely to be widest in [contextMode]; their measured width sets the scroll width. */
    internal fun widestRows(contextMode: UnifiedDiffContextMode): List<UnifiedDiffRow> =
        if (contextMode == UnifiedDiffContextMode.Expanded) widestExpandedRows else widestCollapsedRows

    companion object {
        const val DEFAULT_CONTEXT_LINES = 3
    }
}

internal object UnifiedDiffVisibility {
    /** Rows measured to find the scroll width; the rest are assumed no wider. */
    const val WIDEST_ROW_CANDIDATES = 8

    /**
     * Collapsed rows in two passes: each context row is kept when a change sits within
     * [contextLines] rows before or after it. [onStep] counts the work for tests.
     */
    fun collapsedRows(
        rows: List<UnifiedDiffRow>,
        contextLines: Int,
        onStep: () -> Unit = {},
    ): List<UnifiedDiffRow> {
        val count = rows.size
        val keep = BooleanArray(count)
        var lastChanged = -1
        for (index in 0 until count) {
            onStep()
            if (rows[index].isChange) lastChanged = index
            if (lastChanged >= 0 && index - lastChanged <= contextLines) keep[index] = true
        }
        var nextChanged = -1
        for (index in count - 1 downTo 0) {
            onStep()
            if (rows[index].isChange) nextChanged = index
            if (nextChanged >= 0 && nextChanged - index <= contextLines) keep[index] = true
        }
        val visible = ArrayList<UnifiedDiffRow>(count)
        for (index in 0 until count) {
            val row = rows[index]
            if (row.kind != UnifiedDiffRowKind.Context || keep[index]) visible += row
        }
        return visible
    }

    /** The [limit] rows with the most display columns (wide characters count twice, tabs as a tab stop). */
    fun widestRows(
        rows: List<UnifiedDiffRow>,
        limit: Int = WIDEST_ROW_CANDIDATES,
    ): List<UnifiedDiffRow> {
        if (rows.size <= limit) return rows
        val best = java.util.PriorityQueue<Pair<Int, UnifiedDiffRow>>(limit + 1, compareBy { it.first })
        for (row in rows) {
            best += displayColumns(row.text) to row
            if (best.size > limit) best.poll()
        }
        return best.map { it.second }
    }

    /** Digits in the largest line number of [rows]; the line-number columns are sized for it. */
    fun lineNumberDigits(rows: List<UnifiedDiffRow>): Int {
        var largest = 0
        for (row in rows) largest = maxOf(largest, row.oldLine ?: 0, row.newLine ?: 0)
        return largest.toString().length
    }

    internal fun displayColumns(text: String): Int {
        var columns = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            columns +=
                when {
                    codePoint == '\t'.code -> TAB_COLUMNS
                    isWide(codePoint) -> 2
                    else -> 1
                }
            index += Character.charCount(codePoint)
        }
        return columns
    }

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE30..0xFE4F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD

    private const val TAB_COLUMNS = 8
}

private val UnifiedDiffRow.isChange: Boolean
    get() = kind == UnifiedDiffRowKind.Addition || kind == UnifiedDiffRowKind.Deletion

object UnifiedDiffParser {
    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$")

    /**
     * Parses [patch] in one pass, taking each row's text straight from [patch]. A [truncated]
     * patch was cut at a line end, so its last hunk may hold fewer rows than its header says.
     */
    fun parse(
        patch: String,
        truncated: Boolean = false,
    ): UnifiedDiffDocument {
        if (patch.isEmpty()) return UnifiedDiffDocument(emptyList(), emptyList())
        var oldLine: Int? = null
        var newLine: Int? = null
        var insideHunk = false
        var malformed = false
        val headers = mutableListOf<HunkHeader>()
        val rows = ArrayList<UnifiedDiffRow>()
        // One trailing newline ends the last line rather than starting an empty one.
        val contentEnd = if (patch.endsWith("\n")) patch.length - 1 else patch.length
        var lineStart = 0
        var index = 0
        while (true) {
            val found = patch.indexOf('\n', lineStart)
            val lineEnd = if (found < 0 || found > contentEnd) contentEnd else found
            val first = if (lineStart < lineEnd) patch[lineStart] else null
            val startsHunk = first == '@' && patch.startsWith("@@", lineStart)
            val header = if (startsHunk) hunkHeader.matchEntire(patch.substring(lineStart, lineEnd)) else null
            val row =
                when {
                    header != null -> {
                        val line = header.value
                        val values = header.groupValues
                        val oldStart = values[1].toIntOrNull()
                        val oldCount = values[2].ifEmpty { "1" }.toIntOrNull()
                        val newStart = values[3].toIntOrNull()
                        val newCount = values[4].ifEmpty { "1" }.toIntOrNull()
                        if (oldStart == null || oldCount == null || newStart == null || newCount == null) {
                            malformed = true
                            insideHunk = false
                            UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, line)
                        } else {
                            oldLine = oldStart
                            newLine = newStart
                            insideHunk = true
                            headers += HunkHeader(index, line, oldStart, oldCount, newStart, newCount)
                            UnifiedDiffRow(index, UnifiedDiffRowKind.Hunk, null, null, line)
                        }
                    }
                    insideHunk && first == '+' -> {
                        headers.last().actualNewCount += 1
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Addition, null, newLine, patch.substring(lineStart + 1, lineEnd))
                            .also { newLine = newLine?.plus(1) }
                    }
                    insideHunk && first == '-' -> {
                        headers.last().actualOldCount += 1
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Deletion, oldLine, null, patch.substring(lineStart + 1, lineEnd))
                            .also { oldLine = oldLine?.plus(1) }
                    }
                    first == '\\' && patch.startsWith(NO_NEWLINE_MARKER, lineStart) && lineStart + NO_NEWLINE_MARKER.length <= lineEnd ->
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, patch.substring(lineStart, lineEnd))
                    insideHunk && first == ' ' -> {
                        headers.last().actualOldCount += 1
                        headers.last().actualNewCount += 1
                        UnifiedDiffRow(
                            index,
                            UnifiedDiffRowKind.Context,
                            oldLine,
                            newLine,
                            patch.substring(lineStart + 1, lineEnd),
                        ).also {
                            oldLine = oldLine?.plus(1)
                            newLine = newLine?.plus(1)
                        }
                    }
                    insideHunk -> {
                        malformed = true
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, patch.substring(lineStart, lineEnd))
                    }
                    else -> {
                        if (startsHunk) malformed = true
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, patch.substring(lineStart, lineEnd))
                    }
                }
            rows += row
            if (lineEnd >= contentEnd) break
            lineStart = lineEnd + 1
            index++
        }
        val lastHeader = headers.lastOrNull()
        val invalid =
            headers.any { header ->
                if (truncated && header === lastHeader) !header.fitsDeclaredCounts else !header.isValid
            }
        if (malformed || invalid) return UnifiedDiffDocument(rows, emptyList())
        val hunks =
            headers.mapIndexed { headerIndex, header ->
                val end = headers.getOrNull(headerIndex + 1)?.rowIndex ?: rows.size
                UnifiedDiffHunk(
                    id = header.rowIndex,
                    header = header.text,
                    oldStart = header.oldStart,
                    oldCount = header.oldCount,
                    newStart = header.newStart,
                    newCount = header.newCount,
                    rows = rows.subList(header.rowIndex + 1, end),
                )
            }
        return UnifiedDiffDocument(rows, hunks)
    }

    private const val NO_NEWLINE_MARKER = "\\ No newline at end of file"

    private data class HunkHeader(
        val rowIndex: Int,
        val text: String,
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        var actualOldCount: Int = 0,
        var actualNewCount: Int = 0,
    ) {
        val isValid: Boolean get() = actualOldCount == oldCount && actualNewCount == newCount

        /** A hunk cut short by truncation: no more rows than declared. */
        val fitsDeclaredCounts: Boolean get() = actualOldCount <= oldCount && actualNewCount <= newCount
    }
}

/**
 * Keeps the parsed document for the last patch it saw, so a screen re-rendering the same patch
 * parses it once. [parse] is a seam for tests.
 */
internal class UnifiedDiffDocumentMemo(
    private val parse: (String, Boolean) -> UnifiedDiffDocument = UnifiedDiffParser::parse,
) {
    private var patch: String? = null
    private var truncated = false
    private var document: UnifiedDiffDocument? = null

    fun document(
        patch: String,
        truncated: Boolean = false,
    ): UnifiedDiffDocument {
        val cached = document
        if (cached != null && this.truncated == truncated && this.patch == patch) return cached
        return parse(patch, truncated).also {
            this.patch = patch
            this.truncated = truncated
            document = it
        }
    }
}

/** [patch] parsed once for as long as the caller stays composed with the same patch. */
@Composable
internal fun rememberUnifiedDiffDocument(
    patch: String,
    truncated: Boolean = false,
): UnifiedDiffDocument {
    val memo = remember { UnifiedDiffDocumentMemo() }
    return memo.document(patch, truncated)
}

@Composable
fun OpenCodeUnifiedDiff(
    file: FileDiff,
    modifier: Modifier = Modifier,
    contextMode: UnifiedDiffContextMode = UnifiedDiffContextMode.Collapsed,
    selectedHunkId: Int? = null,
    onSelectedHunkOffset: (Int) -> Unit = {},
    document: UnifiedDiffDocument,
    /** Scroll the selected hunk into view; false keeps the file header in view on open. */
    revealSelectedHunk: Boolean = true,
) {
    val shape = OpenCodeSmallShape
    val fileSummary = file.summary()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .semantics { contentDescription = fileSummary },
        ) {
            UnifiedDiffVirtualRows(
                rows = document.visibleRows(contextMode),
                widestRows = document.widestRows(contextMode),
                selectedHunkId = selectedHunkId,
                revealSelectedHunk = revealSelectedHunk,
                onSelectedHunkOffset = onSelectedHunkOffset,
            )
        }
    }
}

/**
 * Lays [rows] out at one row height and composes only the rows near what the window shows
 * through every clipping parent, so a long diff stays lazy inside a scrolling screen or sheet.
 * The layout is as tall as all rows and as wide as the widest of [widestRows] (or any composed
 * row), so the parent scrolls exactly as it did over a column of every row.
 */
@Composable
private fun UnifiedDiffVirtualRows(
    rows: List<UnifiedDiffRow>,
    widestRows: List<UnifiedDiffRow>,
    selectedHunkId: Int?,
    revealSelectedHunk: Boolean,
    onSelectedHunkOffset: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val overscanPx = maxOf(1, with(density) { screenHeight.roundToPx() })
    // Written during measure, read after layout; it never drives composition.
    val rowHeight = remember { IntArray(1) }
    var window by remember { mutableStateOf<IntRange?>(null) }
    val gutterDigits = remember(rows) { UnifiedDiffVisibility.lineNumberDigits(rows) }
    val selectedIndex =
        remember(rows, selectedHunkId) {
            if (selectedHunkId == null) -1 else rows.indexOfFirst { it.id == selectedHunkId && it.kind == UnifiedDiffRowKind.Hunk }
        }
    val currentOnSelectedHunkOffset by rememberUpdatedState(onSelectedHunkOffset)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(selectedIndex, revealSelectedHunk) {
        if (selectedIndex < 0 || !revealSelectedHunk) return@LaunchedEffect
        withFrameNanos { }
        val height = rowHeight[0].toFloat()
        if (height > 0f) {
            val top = selectedIndex * height
            bringIntoViewRequester.bringIntoView(Rect(0f, top, 1f, top + height))
        }
    }
    SubcomposeLayout(
        Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onGloballyPositioned { coordinates ->
                val height = rowHeight[0]
                if (height <= 0) return@onGloballyPositioned
                if (selectedIndex >= 0) currentOnSelectedHunkOffset(selectedIndex * height)
                val shown = coordinates.boundsInWindow()
                if (shown.isEmpty) return@onGloballyPositioned
                val top = coordinates.windowToLocal(Offset(shown.left, shown.top)).y
                val bottom = coordinates.windowToLocal(Offset(shown.left, shown.bottom)).y
                val next = UnifiedDiffRowWindow.rows(top, bottom, height, overscanPx, rows.size)
                if (next != window) window = next
            },
    ) { constraints ->
        val height =
            subcompose(UnifiedDiffSlot.Probe) { UnifiedDiffRowView(UnifiedDiffProbeRow, selected = false, gutterDigits = gutterDigits) }
                .first()
                .measure(Constraints())
                .height
                .coerceAtLeast(1)
        rowHeight[0] = height
        // Past the largest layout height a row is no longer placed; only a pathological patch gets there.
        val rowCount = minOf(rows.size, MAX_DIFF_LAYOUT_HEIGHT / height)
        val widest =
            widestRows.withIndex().maxOfOrNull { (index, row) ->
                subcompose(UnifiedDiffSlot.Width(index)) { UnifiedDiffRowView(row, selected = false, gutterDigits = gutterDigits) }
                    .first()
                    .measure(Constraints())
                    .width
            } ?: 0
        val shown = window ?: UnifiedDiffRowWindow.rows(0f, 0f, height, overscanPx, rowCount)
        val first = shown.first.coerceIn(0, rowCount)
        val last = (shown.last + 1).coerceIn(first, rowCount)
        val rowConstraints = Constraints(maxHeight = height)
        val placeables =
            (first until last).map { index ->
                val row = rows[index]
                val selected = row.id == selectedHunkId && row.kind == UnifiedDiffRowKind.Hunk
                subcompose(row.id) { UnifiedDiffRowView(row, selected = selected, gutterDigits = gutterDigits) }
                    .first()
                    .measure(rowConstraints)
            }
        val width = maxOf(widest, placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, (height * rowCount).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { offset, placeable -> placeable.place(0, (first + offset) * height) }
        }
    }
}

/** The rows to compose: what is shown plus [overscanPx] each way, in blocks so small scrolls reuse the window. */
internal object UnifiedDiffRowWindow {
    const val BLOCK_ROWS = 32

    fun rows(
        shownTop: Float,
        shownBottom: Float,
        rowHeight: Int,
        overscanPx: Int,
        rowCount: Int,
    ): IntRange {
        val firstRow = ((shownTop - overscanPx) / rowHeight).toInt().coerceAtLeast(0)
        val lastRow = ((maxOf(shownBottom, shownTop) + overscanPx) / rowHeight).toInt().coerceAtLeast(0)
        val first = (firstRow / BLOCK_ROWS) * BLOCK_ROWS
        val last = ((lastRow / BLOCK_ROWS) + 1) * BLOCK_ROWS - 1
        return first.coerceAtMost(rowCount)..last.coerceAtMost(maxOf(0, rowCount - 1))
    }
}

private sealed interface UnifiedDiffSlot {
    data object Probe : UnifiedDiffSlot

    data class Width(
        val index: Int,
    ) : UnifiedDiffSlot
}

/** Measures the row height every row shares: one line of code text. */
/** One code digit at 14 sp monospace, rounded up. */
private val LINE_NUMBER_DIGIT_WIDTH = 9.dp

private val UnifiedDiffProbeRow = UnifiedDiffRow(-1, UnifiedDiffRowKind.Context, 1, 1, "")

/** Compose's largest layout dimension. */
private const val MAX_DIFF_LAYOUT_HEIGHT = (1 shl 24) - 1

@Composable
private fun UnifiedDiffRowView(
    row: UnifiedDiffRow,
    selected: Boolean,
    gutterDigits: Int,
    modifier: Modifier = Modifier,
) {
    // Two digits fit the base width; each further digit widens the column, so a number never wraps
    // into the second line that the one shared row height would clip.
    val gutterWidth = openCodeTextScaled(24.dp + LINE_NUMBER_DIGIT_WIDTH * maxOf(0, gutterDigits - 2))
    val background =
        when (row.kind) {
            UnifiedDiffRowKind.Addition -> openCodeColor("diff-add").copy(alpha = 0.18f)
            UnifiedDiffRowKind.Deletion -> openCodeColor("diff-delete").copy(alpha = 0.16f)
            UnifiedDiffRowKind.Hunk ->
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            UnifiedDiffRowKind.Metadata, UnifiedDiffRowKind.Context -> Color.Transparent
        }
    val foreground =
        when (row.kind) {
            UnifiedDiffRowKind.Addition -> openCodeColor("diff-add")
            UnifiedDiffRowKind.Deletion -> openCodeColor("diff-delete")
            UnifiedDiffRowKind.Hunk, UnifiedDiffRowKind.Metadata -> openCodeColor("syntax-diff-unknown")
            UnifiedDiffRowKind.Context -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier =
            modifier
                .background(background)
                .heightIn(min = 20.dp)
                .clearAndSetSemantics { contentDescription = row.accessibilityLabel() },
    ) {
        Text(row.oldLine?.toString().orEmpty(), Modifier.width(gutterWidth), maxLines = 1, softWrap = false, style = CodeTextStyle)
        Text(row.newLine?.toString().orEmpty(), Modifier.width(gutterWidth), maxLines = 1, softWrap = false, style = CodeTextStyle)
        Text(row.kind.marker(), Modifier.width(openCodeTextScaled(20.dp)), color = foreground, style = CodeTextStyle)
        Text(
            text = row.text,
            modifier = Modifier.padding(horizontal = 8.dp),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
            style = CodeTextStyle,
        )
    }
}

@Composable
private fun FileDiff.summary(): String =
    stringResource(
        when (status) {
            FileDiffStatus.Added -> R.string.component_unified_diff_summary_added
            FileDiffStatus.Deleted -> R.string.component_unified_diff_summary_deleted
            FileDiffStatus.Modified -> R.string.component_unified_diff_summary_modified
        },
        path,
        additions,
        deletions,
    )

private fun UnifiedDiffRowKind.marker() =
    when (this) {
        UnifiedDiffRowKind.Addition -> "+"
        UnifiedDiffRowKind.Deletion -> "−"
        else -> ""
    }

private fun UnifiedDiffRow.accessibilityLabel() =
    listOfNotNull(kind.name.lowercase(), oldLine?.let { "old line $it" }, newLine?.let { "new line $it" }, text).joinToString(", ")
