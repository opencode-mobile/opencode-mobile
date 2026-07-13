package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.FileDiffDto
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
        val rows = UnifiedDiffParser.parse("@@ -2,3 +2,3 @@\n same\n-old\n\\ No newline at end of file\n+new\n next")
        assertEquals(
            listOf(
                UnifiedDiffRowKind.Hunk,
                UnifiedDiffRowKind.Context,
                UnifiedDiffRowKind.Deletion,
                UnifiedDiffRowKind.Metadata,
                UnifiedDiffRowKind.Addition,
                UnifiedDiffRowKind.Context,
            ),
            rows.map {
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
    }
}
