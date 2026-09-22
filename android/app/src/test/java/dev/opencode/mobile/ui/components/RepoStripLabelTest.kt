package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoStripLabelTest {
    private val files =
        listOf(
            OpenCodeRepoStripFile("a/b/HealthApi.kt", OpenCodeRepoStripFileStatus.Modified, 42, 7),
            OpenCodeRepoStripFile("HealthProbeTest.kt", OpenCodeRepoStripFileStatus.Added, 61, 0),
            OpenCodeRepoStripFile("docs/health-probe.md", OpenCodeRepoStripFileStatus.Deleted, 0, 19),
        )

    @Test
    fun summary_sumsFilesAndLines() {
        val summary = formatRepoStripSummary(files)
        assertEquals("3 files", summary.fileLabel)
        assertEquals("+103", summary.additionLabel)
        assertEquals("−26", summary.deletionLabel)
    }

    @Test
    fun summary_singleFileIsSingular() {
        assertEquals("1 file", formatRepoStripSummary(files.take(1)).fileLabel)
    }

    @Test
    fun summary_cleanTreeHasNoLineLabels() {
        val summary = formatRepoStripSummary(emptyList())
        assertTrue(summary.isClean)
        assertEquals("clean", summary.fileLabel)
        assertNull(summary.additionLabel)
        assertNull(summary.deletionLabel)
    }

    @Test
    fun contentDescription_dirtyIncludesDisclosure() {
        assertEquals(
            "Branch fix/probe, 3 files changed, 103 additions, 26 deletions, expanded",
            formatRepoStripContentDescription("fix/probe", files, expanded = true),
        )
        assertEquals(
            "Branch fix/probe, 3 files changed, 103 additions, 26 deletions, collapsed",
            formatRepoStripContentDescription("fix/probe", files, expanded = false),
        )
    }

    @Test
    fun contentDescription_cleanHasNoDisclosure() {
        assertEquals(
            "Branch main, clean working tree",
            formatRepoStripContentDescription("main", emptyList(), expanded = true),
        )
    }

    @Test
    fun statusLetters() {
        assertEquals("A", repoStripStatusLetter(OpenCodeRepoStripFileStatus.Added))
        assertEquals("M", repoStripStatusLetter(OpenCodeRepoStripFileStatus.Modified))
        assertEquals("D", repoStripStatusLetter(OpenCodeRepoStripFileStatus.Deleted))
    }
}
