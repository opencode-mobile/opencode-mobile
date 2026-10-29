package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchRange
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.ui.components.OpenCodeCodeMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LabHighlightTest {
    @Test
    fun engineRolesMapToDesignSystemRolesAndUnknownOnesStayPlain() {
        val spans = listOf(HighlightBenchSpan(0, 3, "Keyword"), HighlightBenchSpan(4, 8, "NotARole"))
        assertEquals(listOf(CodeTokenRole.Keyword), spans.toCodeSpans().map { it.role })
    }

    @Test
    fun fileDataSetsTakeTheExtensionsLanguageAndAreReusedForTheSameText() {
        val store = LabDataSetStore(emptyList())
        val first = store.addFile("/Users/example/project/Main.kt", KOTLIN_SOURCE)
        assertEquals("kotlin", first.language)
        assertEquals("Main.kt", first.displayName)
        assertEquals(first.id, store.addFile("/Users/example/project/Main.kt", KOTLIN_SOURCE).id)
        // Edited since: a new data set, so earlier timings stay attached to the old text.
        val edited = store.addFile("/Users/example/project/Main.kt", KOTLIN_SOURCE + "\n")
        assertTrue(edited.id != first.id)
        assertEquals(LabDataSetStore.PLAIN_TEXT, store.addFile("/Users/example/project/notes.unknownext", "x").language)
        assertEquals(3, store.all().size)
    }

    @Test
    fun marksSplitAcrossLines() {
        val lines = labCodeLines("abc\ndef\n", emptyList(), listOf(HighlightBenchRange(2, 6)))
        assertEquals(listOf(OpenCodeCodeMark(2, 3)), lines[0].marks)
        assertEquals(listOf(OpenCodeCodeMark(0, 2)), lines[1].marks)
    }

    @Test
    fun reportStoreWritesLatestTimestampedAndMirroredCopies() {
        val root =
            kotlin.io.path
                .createTempDirectory("lab-store")
                .toFile()
        val primary = File(root, "files/highlight-lab")
        val mirror = File(root, "external/highlight-lab")
        val store = LabReportStore(primary, listOf(mirror), now = { 0L })

        val saved = store.save("# Report", "{}")

        assertEquals(File(primary, "latest.md"), saved.markdownFile)
        listOf(primary, mirror).forEach { dir ->
            assertEquals("# Report", File(dir, "latest.md").readText())
            assertEquals("{}", File(dir, "latest.json").readText())
            assertEquals(2, dir.listFiles { file -> file.name.startsWith("run-") }?.size)
        }
        assertEquals("{}", store.latest()?.json)
        assertTrue(LabReportStore(File(root, "none")).latest() == null)
        root.deleteRecursively()
    }
}
