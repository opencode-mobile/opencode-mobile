package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffDocumentTest {
    @Test fun collapsedRowsMatchTheQuadraticDefinitionOnEveryFixture() {
        for ((name, patch) in fixtures) {
            val document = UnifiedDiffParser.parse(patch)
            for (contextLines in listOf(0, 1, 3, 5)) {
                assertEquals(
                    "$name, context $contextLines",
                    quadraticCollapsedRows(document.rows, contextLines),
                    document.visibleRows(UnifiedDiffContextMode.Collapsed, contextLines),
                )
            }
            assertEquals(name, document.rows, document.visibleRows(UnifiedDiffContextMode.Expanded))
        }
    }

    @Test fun collapsedRowsTakeLinearWorkInTheRowCount() {
        val small = UnifiedDiffParser.parse(sparsePatch(10_000)).rows
        val large = UnifiedDiffParser.parse(sparsePatch(40_000)).rows
        val smallSteps = steps(small)
        val largeSteps = steps(large)
        // Two passes over the rows: exactly twice the row count, so 4x rows is 4x work.
        assertEquals(2 * small.size, smallSteps)
        assertEquals(2 * large.size, largeSteps)
        assertEquals(quadraticCollapsedRows(small, 3), UnifiedDiffVisibility.collapsedRows(small, 3))
    }

    @Test fun collapsedRowsAreComputedOncePerDocument() {
        val document = UnifiedDiffParser.parse(sparsePatch(2_000))
        val first = document.visibleRows(UnifiedDiffContextMode.Collapsed)
        assertSame(first, document.visibleRows(UnifiedDiffContextMode.Collapsed))
        assertTrue(document.canExpandContext)
        assertFalse(UnifiedDiffParser.parse("@@ -1 +1 @@\n-old\n+new").canExpandContext)
    }

    @Test fun memoParsesAPatchOncePerPatchValue() {
        var parses = 0
        val memo =
            UnifiedDiffDocumentMemo { patch, truncated ->
                parses++
                UnifiedDiffParser.parse(patch, truncated)
            }
        val patch = sparsePatch(500)
        val first = memo.document(patch)
        repeat(10) { assertSame(first, memo.document(patch)) }
        assertEquals(1, parses)
        memo.document("@@ -1 +1 @@\n-a\n+b")
        assertEquals(2, parses)
    }

    @Test fun truncatedPatchKeepsItsCutLastHunk() {
        val cut = "@@ -1 +1 @@\n-a\n+b\n@@ -10,5 +10,5 @@\n same\n-old"
        assertTrue(UnifiedDiffParser.parse(cut).hunks.isEmpty())
        val document = UnifiedDiffParser.parse(cut, truncated = true)
        assertEquals(2, document.hunks.size)
        assertEquals(listOf("same", "old"), document.hunks[1].rows.map { it.text })
        // Only the last hunk may come up short.
        assertTrue(UnifiedDiffParser.parse("@@ -1,2 +1 @@\n-a\n+b\n@@ -10 +10 @@\n-c\n+d", truncated = true).hunks.isEmpty())
    }

    @Test fun parserKeepsCarriageReturnsAndTrailingEmptyLines() {
        val document = UnifiedDiffParser.parse("@@ -1,2 +1,2 @@\n-a\r\n+b\r\n \n")
        assertEquals(listOf("@@ -1,2 +1,2 @@", "a\r", "b\r", ""), document.rows.map { it.text })
        assertEquals(1, document.hunks.size)
        assertEquals(listOf(""), UnifiedDiffParser.parse("\n").rows.map { it.text })
    }

    @Test fun widestRowsPickTheRowsWithMostColumns() {
        val rows = UnifiedDiffParser.parse(sparsePatch(200)).rows
        val wide = UnifiedDiffParser.parse("@@ -0,0 +1,2 @@\n+${"x".repeat(300)}\n+${"界".repeat(200)}").rows
        val widest = UnifiedDiffVisibility.widestRows(rows + wide, limit = 2)
        assertEquals(setOf("x".repeat(300), "界".repeat(200)), widest.map { it.text }.toSet())
        assertEquals(400, UnifiedDiffVisibility.displayColumns("界".repeat(200)))
    }

    @Test fun lineNumberColumnsAreSizedForTheLargestLineNumber() {
        assertEquals(1, UnifiedDiffVisibility.lineNumberDigits(UnifiedDiffParser.parse("@@ -1,1 +1,1 @@\n-a\n+b").rows))
        val long = UnifiedDiffParser.parse("@@ -9998,2 +9998,3 @@\n a\n-b\n+c\n+d").rows
        // New line numbers reach 10000, five digits; one shared row height must never wrap them.
        assertEquals(5, UnifiedDiffVisibility.lineNumberDigits(long))
        assertEquals(1, UnifiedDiffVisibility.lineNumberDigits(UnifiedDiffParser.parse("").rows))
    }

    @Test fun rowWindowCoversTheShownRowsPlusOverscanInBlocks() {
        val window =
            UnifiedDiffRowWindow.rows(
                shownTop = 2_000f,
                shownBottom = 3_000f,
                rowHeight = 20,
                overscanPx = 1_000,
                rowCount = 10_000,
            )
        assertEquals(32, window.first)
        assertTrue(window.last >= 200)
        assertEquals(0, (window.last + 1) % UnifiedDiffRowWindow.BLOCK_ROWS)
        // Before the first layout reports what shows: one overscan (a screen) of rows from the top.
        assertEquals(0..63, UnifiedDiffRowWindow.rows(0f, 0f, 20, 1_000, rowCount = 100))
        assertEquals(0..9, UnifiedDiffRowWindow.rows(0f, 0f, 20, 1_000, rowCount = 10))
    }

    private fun steps(rows: List<UnifiedDiffRow>): Int {
        var count = 0
        UnifiedDiffVisibility.collapsedRows(rows, 3) { count++ }
        return count
    }

    /** The original projection: every context row checked against every changed row. */
    private fun quadraticCollapsedRows(
        rows: List<UnifiedDiffRow>,
        contextLines: Int,
    ): List<UnifiedDiffRow> {
        val changed =
            rows.indices.filter { rows[it].kind == UnifiedDiffRowKind.Addition || rows[it].kind == UnifiedDiffRowKind.Deletion }
        return rows.filterIndexed { index, row ->
            row.kind != UnifiedDiffRowKind.Context || changed.any { kotlin.math.abs(index - it) <= contextLines }
        }
    }

    /** One hunk of [lines] context rows with a change every 50 rows. */
    private fun sparsePatch(lines: Int): String {
        val body = StringBuilder()
        var old = 0
        var new = 0
        for (index in 0 until lines) {
            when {
                index % 50 == 25 -> body.append("-gone $index\n").also { old++ }
                index % 50 == 26 -> body.append("+added $index\n").also { new++ }
                else ->
                    body.append(" line $index\n").also {
                        old++
                        new++
                    }
            }
        }
        return "@@ -1,$old +1,$new @@\n$body"
    }

    private val fixtures: List<Pair<String, String>> =
        listOf(
            "existing two hunks" to
                "@@ -2,3 +2,3 @@ first\n same\n-old\n\\ No newline at end of file\n+new\n next\n@@ -20 +20,2 @@ second\n+naïve 🚀\n tail",
            "existing collapse" to
                "@@ -1,17 +1,17 @@\n${(1..8).joinToString(
                    "\n",
                ) { " before $it" }}\n-old\n+new\n${(1..8).joinToString("\n") { " after $it" }}",
            "change at start" to "@@ -1,8 +1,8 @@\n-a\n+b\n${(1..7).joinToString("\n") { " c$it" }}",
            "change at end" to "@@ -1,8 +1,8 @@\n${(1..7).joinToString("\n") { " c$it" }}\n-a\n+b",
            "adjacent hunks" to "@@ -1,3 +1,3 @@\n c1\n-a\n+b\n c2\n@@ -5,3 +5,3 @@\n c3\n-d\n+e\n c4",
            "no context" to "@@ -1,2 +1,2 @@\n-a\n-b\n+c\n+d",
            "context only" to "@@ -1,3 +1,3 @@\n a\n b\n c",
            "metadata" to "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,9 +1,9 @@\n${(1..8).joinToString("\n") { " c$it" }}\n-z\n+y",
            "sparse" to sparsePatch(400),
        )
}
