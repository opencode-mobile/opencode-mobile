package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingPolicyTest {
    @Test
    fun directories_keepsHiddenFolders_dropsFilesAndDotEntries() {
        val nodes =
            listOf(
                DirectoryNode(name = "zeta", absolutePath = "/home/zeta", isDirectory = true),
                DirectoryNode(name = "readme.md", absolutePath = "/home/readme.md", isDirectory = false),
                DirectoryNode(name = ".hidden", absolutePath = "/home/.hidden", isDirectory = true),
                DirectoryNode(name = ".", absolutePath = "/home", isDirectory = true),
                DirectoryNode(name = "..", absolutePath = "/", isDirectory = true),
                DirectoryNode(name = "alpha", absolutePath = "/home/alpha", isDirectory = true),
            )

        val directories = DirectoryListingPolicy.directories(nodes)

        assertEquals(
            listOf("/home/.hidden", "/home/alpha", "/home/zeta"),
            directories.map { it.absolutePath },
        )
        assertTrue(DirectoryListingPolicy.isHidden(directories.first()))
    }

    @Test
    fun directories_sortsCaseInsensitively_withoutCap() {
        val nodes =
            listOf("beta", "Alpha", "gamma", "Delta").map { name ->
                DirectoryNode(name = name, absolutePath = "/home/$name", isDirectory = true)
            } + folders(300)

        val directories = DirectoryListingPolicy.directories(nodes)

        assertEquals(304, directories.size)
        assertEquals(listOf("Alpha", "beta", "Delta", "dir-000"), directories.take(4).map { it.name })
        assertEquals("gamma", directories.last().name)
    }

    @Test
    fun visibleEntries_capsAfterFiltering() {
        val entries = DirectoryListingPolicy.directories(folders(250))

        val unfiltered = DirectoryListingPolicy.visibleEntries(entries, filter = "")
        assertEquals(DirectoryListingPolicy.MAX_VISIBLE_DIRECTORIES, unfiltered.rows.size)
        assertEquals("dir-000", unfiltered.rows.first().name)
        assertEquals("dir-199", unfiltered.rows.last().name)
        assertTrue(unfiltered.isTruncated)

        val pastCap = DirectoryListingPolicy.visibleEntries(entries, filter = "DIR-24")
        assertEquals((240..249).map { "dir-$it" }, pastCap.rows.map { it.name })
        assertFalse(pastCap.isTruncated)

        val exactlyAtLimit = DirectoryListingPolicy.visibleEntries(entries, filter = "dir-1", limit = 100)
        assertEquals(100, exactlyAtLimit.rows.size)
        assertFalse(exactlyAtLimit.isTruncated)
    }

    @Test
    fun visibleEntries_showsHiddenFoldersOnlyForDotFilter() {
        val entries =
            DirectoryListingPolicy.directories(
                listOf(".config", ".cache", "code", "docs").map { name ->
                    DirectoryNode(name = name, absolutePath = "/home/$name", isDirectory = true)
                },
            )

        assertEquals(
            listOf("code", "docs"),
            DirectoryListingPolicy.visibleEntries(entries, filter = "").rows.map { it.name },
        )
        assertEquals(
            listOf("code"),
            DirectoryListingPolicy.visibleEntries(entries, filter = "c").rows.map { it.name },
        )
        assertEquals(
            listOf(".cache", ".config"),
            DirectoryListingPolicy.visibleEntries(entries, filter = ".").rows.map { it.name },
        )
        assertEquals(
            listOf(".config"),
            DirectoryListingPolicy.visibleEntries(entries, filter = ".co").rows.map { it.name },
        )
    }

    private fun folders(count: Int): List<DirectoryNode> =
        (0 until count).reversed().map { index ->
            val name = "dir-%03d".format(index)
            DirectoryNode(name = name, absolutePath = "/home/$name", isDirectory = true)
        }
}
