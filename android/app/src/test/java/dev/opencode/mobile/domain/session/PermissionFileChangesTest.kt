package dev.opencode.mobile.domain.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionFileChangesTest {
    private fun metadata(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun v2Files_useServerCounts() {
        val changes =
            PermissionFileChanges.from(
                metadata(
                    """{"files":[{"file":"README.md","patch":"@@\n-a\n+b","additions":1,"deletions":1},""" +
                        """{"file":"notes/todo.md","patch":"--- a\n+++ b\n+one\n+two"}]}""",
                ),
            )
        assertEquals(
            listOf(PermissionFileChange("README.md", 1, 1), PermissionFileChange("notes/todo.md", 2, 0)),
            changes,
        )
    }

    @Test
    fun v1FilepathAndDiff() {
        val changes =
            PermissionFileChanges.from(
                metadata("""{"filepath":"src/app.ts","diff":"--- a/src/app.ts\n+++ b/src/app.ts\n@@\n-old\n+new\n+more"}"""),
            )
        assertEquals(listOf(PermissionFileChange("src/app.ts", 2, 1)), changes)
    }

    @Test
    fun shellPermission_hasNoFiles() {
        assertTrue(PermissionFileChanges.from(metadata("""{"command":"git status"}""")).isEmpty())
    }
}
