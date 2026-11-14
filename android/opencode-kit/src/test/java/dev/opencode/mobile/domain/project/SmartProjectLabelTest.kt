package dev.opencode.mobile.domain.project

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartProjectLabelTest {
    @Test
    fun uniqueBasename_returnsNameOnly() {
        val label =
            smartProjectLabel(
                focusWorktree = "/Users/example/Projects/opencode",
                peerWorktrees =
                    listOf(
                        "/Users/example/Projects/opencode",
                        "/Users/example/Projects/dotfiles",
                        "/Users/example/Documents/notes",
                    ),
            )
        assertEquals("opencode", label)
    }

    @Test
    fun emptyPeers_returnsBasename() {
        assertEquals(
            "opencode",
            smartProjectLabel("/Users/example/Projects/opencode", emptyList()),
        )
    }

    @Test
    fun sameNameDifferentParent_usesOneParent() {
        val peers =
            listOf(
                "/Users/example/Projects/opencode",
                "/Users/example/Documents/opencode",
            )
        assertEquals(
            "..Projects/opencode",
            smartProjectLabel("/Users/example/Projects/opencode", peers),
        )
        assertEquals(
            "..Documents/opencode",
            smartProjectLabel("/Users/example/Documents/opencode", peers),
        )
    }

    @Test
    fun sharedImmediateParent_walksUp() {
        val peers =
            listOf(
                "/Users/example/Work/acme/lib/opencode",
                "/Users/example/Work/beta/lib/opencode",
                "/Users/example/Projects/opencode",
            )
        assertEquals(
            "..acme/lib/opencode",
            smartProjectLabel("/Users/example/Work/acme/lib/opencode", peers),
        )
        assertEquals(
            "..beta/lib/opencode",
            smartProjectLabel("/Users/example/Work/beta/lib/opencode", peers),
        )
        assertEquals(
            "..Projects/opencode",
            smartProjectLabel("/Users/example/Projects/opencode", peers),
        )
    }

    @Test
    fun trailingSlash_normalized() {
        assertEquals(
            "..Projects/opencode",
            smartProjectLabel(
                "/Users/example/Projects/opencode/",
                listOf("/Users/example/Documents/opencode/"),
            ),
        )
    }
}
