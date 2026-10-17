package dev.opencode.mobile.domain.worktree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceDirectoryParserTest {
    @Test
    fun parsesAStringArrayAndObjectDirectories() {
        assertEquals(listOf("/a", "/b"), WorkspaceDirectoryParser.parse("""["/a","/b"]"""))
        assertEquals(
            listOf("/repo", "/worktrees/crisp-cactus"),
            WorkspaceDirectoryParser.parse(
                """{"data":[{"directory":"/repo","name":"main","branch":"main"},{"directory":"/worktrees/crisp-cactus","id":"wrk_1"}]}""",
            ),
        )
    }

    @Test
    fun skipsBlankAndNullDirectories() {
        assertEquals(
            listOf("/repo"),
            WorkspaceDirectoryParser.parse("""[{"directory":"  "},{"directory":null},{"directory":"/repo"}]"""),
        )
    }

    @Test
    fun unrecognizedShapesFailRatherThanStop() {
        assertNull(WorkspaceDirectoryParser.parse("""{"workspaces":["/repo"]}"""))
        assertNull(WorkspaceDirectoryParser.parse("""{"data":{"directory":"/repo"}}"""))
        assertNull(WorkspaceDirectoryParser.parse("not-json"))
        assertNull(WorkspaceDirectoryParser.parse("""[1]"""))
        assertNull(WorkspaceDirectoryParser.parse("""[{"directory":1}]"""))
    }
}
