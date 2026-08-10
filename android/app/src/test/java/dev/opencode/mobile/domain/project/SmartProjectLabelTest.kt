package dev.opencode.mobile.domain.project

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartProjectLabelTest {
    @Test
    fun uniqueBasename_returnsNameOnly() {
        val label =
            smartProjectLabel(
                focusWorktree = "/Users/ivan/Projects/opencode",
                peerWorktrees =
                    listOf(
                        "/Users/ivan/Projects/opencode",
                        "/Users/ivan/Projects/dotfiles",
                        "/Users/ivan/Documents/notes",
                    ),
            )
        assertEquals("opencode", label)
    }

    @Test
    fun emptyPeers_returnsBasename() {
        assertEquals(
            "opencode",
            smartProjectLabel("/Users/ivan/Projects/opencode", emptyList()),
        )
    }

    @Test
    fun sameNameDifferentParent_usesOneParent() {
        val peers =
            listOf(
                "/Users/ivan/Projects/opencode",
                "/Users/ivan/Documents/opencode",
            )
        assertEquals(
            "..Projects/opencode",
            smartProjectLabel("/Users/ivan/Projects/opencode", peers),
        )
        assertEquals(
            "..Documents/opencode",
            smartProjectLabel("/Users/ivan/Documents/opencode", peers),
        )
    }

    @Test
    fun sharedImmediateParent_walksUp() {
        val peers =
            listOf(
                "/Users/ivan/Work/acme/lib/opencode",
                "/Users/ivan/Work/beta/lib/opencode",
                "/Users/ivan/Projects/opencode",
            )
        assertEquals(
            "..acme/lib/opencode",
            smartProjectLabel("/Users/ivan/Work/acme/lib/opencode", peers),
        )
        assertEquals(
            "..beta/lib/opencode",
            smartProjectLabel("/Users/ivan/Work/beta/lib/opencode", peers),
        )
        assertEquals(
            "..Projects/opencode",
            smartProjectLabel("/Users/ivan/Projects/opencode", peers),
        )
    }

    @Test
    fun trailingSlash_normalized() {
        assertEquals(
            "..Projects/opencode",
            smartProjectLabel(
                "/Users/ivan/Projects/opencode/",
                listOf("/Users/ivan/Documents/opencode/"),
            ),
        )
    }
}
