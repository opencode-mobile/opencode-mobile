package dev.opencode.mobile.domain.worktree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorktreeDirectoryParserTest {
    @Test
    fun parsesAStringArrayAndObjectDirectories() {
        assertEquals(listOf("/a", "/b"), WorktreeDirectoryParser.parse("""["/a","/b"]"""))
        assertEquals(
            listOf("/repo", "/worktrees/crisp-cactus"),
            WorktreeDirectoryParser.parse(
                """{"data":[{"directory":"/repo","name":"main","branch":"main"},{"directory":"/worktrees/crisp-cactus","id":"wrk_1"}]}""",
            ),
        )
    }

    @Test
    fun skipsBlankAndNullDirectories() {
        assertEquals(
            listOf("/repo"),
            WorktreeDirectoryParser.parse("""[{"directory":"  "},{"directory":null},{"directory":"/repo"}]"""),
        )
    }

    @Test
    fun unrecognizedShapesFailRatherThanStop() {
        assertNull(WorktreeDirectoryParser.parse("""{"workspaces":["/repo"]}"""))
        assertNull(WorktreeDirectoryParser.parse("""{"data":{"directory":"/repo"}}"""))
        assertNull(WorktreeDirectoryParser.parse("not-json"))
        assertNull(WorktreeDirectoryParser.parse("""[1]"""))
        assertNull(WorktreeDirectoryParser.parse("""[{"directory":1}]"""))
    }
}
