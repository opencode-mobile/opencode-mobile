package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.FileDiffDto
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.UnifiedDiffParser
import dev.opencode.mobile.ui.components.UnifiedDiffRowKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDiffAdapterTest {
    @Test fun decodesServerShapeAndSynthesizesDomainPatchAndStatus() {
        val dto =
            Json.decodeFromString<FileDiffDto>(
                """{"file":"Sources/App.kt","before":"old","after":"new","additions":1,"deletions":1}""",
            )
        val diff = SessionDomainAdapter().toFileDiff(dto)
        assertEquals("Sources/App.kt", diff.path)
        assertEquals(FileDiffStatus.Modified, diff.status)
        assertEquals("@@ -1,1 +1,1 @@\n-old\n+new", diff.patch)
    }

    @Test fun decodesPathPatchAndExplicitStatus() {
        val dto =
            Json.decodeFromString<FileDiffDto>(
                """{"path":"new.txt","patch":"@@ -0,0 +1,1 @@\n+hello","additions":1,"status":"added"}""",
            )
        val diff = SessionDomainAdapter().toFileDiff(dto)
        assertEquals(FileDiffStatus.Added, diff.status)
        assertTrue(diff.patch.orEmpty().contains("+hello"))
    }

    @Test fun synthesizesAddedAndDeletedFilesWithoutPhantomBlankLines() {
        val addedDto =
            Json.decodeFromString<FileDiffDto>(
                """{"file":"new.txt","before":"","after":"hello","additions":1}""",
            )
        val deletedDto =
            Json.decodeFromString<FileDiffDto>(
                """{"file":"old.txt","before":"goodbye","after":"","deletions":1}""",
            )
        val added = SessionDomainAdapter().toFileDiff(addedDto)
        val deleted = SessionDomainAdapter().toFileDiff(deletedDto)
        assertEquals("@@ -0,0 +1,1 @@\n+hello", added.patch)
        assertEquals("@@ -1,1 +0,0 @@\n-goodbye", deleted.patch)
    }

    @Test fun parserAdvancesOldAndNewLineNumbers() {
        val patch =
            "@@ -2,3 +2,3 @@ first\n same\n-old\n\\ No newline at end of file\n+new\n next\n" +
                "@@ -20 +20,2 @@ second\n+naïve 🚀\n tail"
        val document = UnifiedDiffParser.parse(patch)
        val rows = document.rows
        assertEquals(
            listOf(
                UnifiedDiffRowKind.Hunk,
                UnifiedDiffRowKind.Context,
                UnifiedDiffRowKind.Deletion,
                UnifiedDiffRowKind.Metadata,
                UnifiedDiffRowKind.Addition,
                UnifiedDiffRowKind.Context,
            ),
            rows.take(6).map {
                it.kind
            },
        )
        assertEquals(2, rows[1].oldLine)
        assertEquals(2, rows[1].newLine)
        assertEquals(3, rows[2].oldLine)
        assertEquals(null, rows[3].oldLine)
        assertEquals(null, rows[3].newLine)
        assertEquals(3, rows[4].newLine)
        assertEquals(4, rows[5].oldLine)
        assertEquals(4, rows[5].newLine)
        assertEquals(2, document.hunks.size)
        assertEquals(
            listOf(2, 3, 2, 3),
            document.hunks[0].let { listOf(it.oldStart, it.oldCount, it.newStart, it.newCount) },
        )
        assertEquals(
            listOf(20, 1, 20, 2),
            document.hunks[1].let { listOf(it.oldStart, it.oldCount, it.newStart, it.newCount) },
        )
        assertTrue(document.hunks[1].rows.any { it.text == "naïve 🚀" })
    }

    @Test fun collapsedProjectionKeepsThreeContextLinesAroundChanges() {
        val before = (1..8).joinToString("\n") { " before $it" }
        val after = (1..8).joinToString("\n") { " after $it" }
        val document = UnifiedDiffParser.parse("@@ -1,17 +1,17 @@\n$before\n-old\n+new\n$after")

        assertEquals(19, document.visibleRows(UnifiedDiffContextMode.Expanded).size)
        val collapsed = document.visibleRows(UnifiedDiffContextMode.Collapsed)
        assertEquals(9, collapsed.size)
        assertTrue(collapsed.none { it.text == "before 4" })
        assertTrue(collapsed.any { it.text == "before 6" })
        assertTrue(collapsed.any { it.text == "after 3" })
        assertTrue(collapsed.none { it.text == "after 4" })
    }

    @Test fun metadataEmptyAndBinaryPatchesDoNotCreatePhantomHunks() {
        assertTrue(UnifiedDiffParser.parse("").hunks.isEmpty())
        assertTrue(UnifiedDiffParser.parse("Binary files a/image.png and b/image.png differ").hunks.isEmpty())
        val document = UnifiedDiffParser.parse("--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new")
        assertTrue(document.rows.take(2).all { it.kind == UnifiedDiffRowKind.Metadata })
    }

    @Test fun rejectsMalformedHunksAndIgnoresOneTrailingPatchNewline() {
        assertTrue(UnifiedDiffParser.parse("@@ -1,2 +1,2 @@\n-old\n+new").hunks.isEmpty())
        assertTrue(UnifiedDiffParser.parse("@@ -1 +1 @@\nbare row").hunks.isEmpty())
        assertTrue(UnifiedDiffParser.parse("@@ -1 +1 @@garbage\n-old\n+new").hunks.isEmpty())
        assertTrue(
            UnifiedDiffParser.parse("@@ -99999999999999999999 +1 @@\n-old\n+new").hunks.isEmpty(),
        )

        val trailingNewline = UnifiedDiffParser.parse("@@ -1 +1 @@\n-old\n+new\n")
        assertEquals(1, trailingNewline.hunks.size)
        assertEquals(3, trailingNewline.rows.size)
    }

    @Test fun preservesLongSourceLinesExactly() {
        val longLine = "result = buildAnIntentionallyLongLineThatRequiresHorizontalScrollingToReviewSafely()"
        val document = UnifiedDiffParser.parse("@@ -0,0 +1 @@\n+$longLine")
        assertEquals(
            longLine,
            document.hunks
                .single()
                .rows
                .single()
                .text,
        )
    }
}
