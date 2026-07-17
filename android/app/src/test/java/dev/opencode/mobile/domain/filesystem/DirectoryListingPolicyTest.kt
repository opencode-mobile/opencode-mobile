package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryListingPolicyTest {
    @Test
    fun prepare_keepsVisibleDirectoriesOnly_sortedByName() {
        val nodes =
            listOf(
                DirectoryNode(name = "zeta", absolutePath = "/home/zeta", isDirectory = true),
                DirectoryNode(name = "alpha", absolutePath = "/home/alpha", isDirectory = true),
                DirectoryNode(name = "readme.md", absolutePath = "/home/readme.md", isDirectory = false),
                DirectoryNode(name = ".hidden", absolutePath = "/home/.hidden", isDirectory = true),
                DirectoryNode(name = "..", absolutePath = "/home/..", isDirectory = true),
            )

        val prepared = DirectoryListingPolicy.prepare(nodes)

        assertEquals(
            listOf("/home/alpha", "/home/zeta"),
            prepared.map { it.absolutePath },
        )
    }

    @Test
    fun prepare_truncatesLongListings() {
        val nodes =
            (1..250).map { index ->
                DirectoryNode(
                    name = "dir-%03d".format(index),
                    absolutePath = "/home/dir-%03d".format(index),
                    isDirectory = true,
                )
            }

        val prepared = DirectoryListingPolicy.prepare(nodes)

        assertEquals(DirectoryListingPolicy.MAX_VISIBLE_DIRECTORIES, prepared.size)
        assertEquals("/home/dir-001", prepared.first().absolutePath)
        assertEquals(
            "/home/dir-%03d".format(DirectoryListingPolicy.MAX_VISIBLE_DIRECTORIES),
            prepared.last().absolutePath,
        )
    }

    @Test
    fun parentPath_returnsParentUntilRoot() {
        assertEquals("/Users/ivan", DirectoryListingPolicy.parentPath("/Users/ivan/Projects"))
        assertEquals("/Users", DirectoryListingPolicy.parentPath("/Users/ivan"))
        assertEquals("/", DirectoryListingPolicy.parentPath("/Users"))
        assertEquals(null, DirectoryListingPolicy.parentPath("/"))
        assertEquals(null, DirectoryListingPolicy.parentPath(""))
    }
}
