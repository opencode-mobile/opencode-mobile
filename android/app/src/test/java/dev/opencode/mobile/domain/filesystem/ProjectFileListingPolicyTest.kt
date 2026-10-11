package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFileListingPolicyTest {
    private val entries =
        listOf(
            ProjectEntry("zeta.txt", "zeta.txt", isDirectory = false),
            ProjectEntry(".github", ".github", isDirectory = true),
            ProjectEntry("Alpha.kt", "Alpha.kt", isDirectory = false),
            ProjectEntry("docs", "docs", isDirectory = true),
            ProjectEntry(".env", ".env", isDirectory = false),
            ProjectEntry("beta.kt", "beta.kt", isDirectory = false),
            ProjectEntry("Build", "Build", isDirectory = true),
        )

    @Test
    fun sorted_putsFoldersFirstThenCaseInsensitiveName() {
        assertEquals(
            listOf(".github", "Build", "docs", ".env", "Alpha.kt", "beta.kt", "zeta.txt"),
            ProjectFileListingPolicy.sorted(entries).map { it.name },
        )
    }

    @Test
    fun visible_hidesDotEntriesUnlessShowHidden() {
        val sorted = ProjectFileListingPolicy.sorted(entries)
        assertEquals(
            listOf("Build", "docs", "Alpha.kt", "beta.kt", "zeta.txt"),
            ProjectFileListingPolicy.visible(sorted, showHidden = false).map { it.name },
        )
        assertEquals(sorted, ProjectFileListingPolicy.visible(sorted, showHidden = true))
    }

    @Test
    fun sorted_keepsFilesAndHasNoCap() {
        val many = (1..500).map { ProjectEntry("file$it.txt", "file$it.txt", isDirectory = false) }
        assertEquals(500, ProjectFileListingPolicy.sorted(many).size)
    }
}
