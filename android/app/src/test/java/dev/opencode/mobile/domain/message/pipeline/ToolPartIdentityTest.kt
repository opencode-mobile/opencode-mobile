package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPartIdentityTest {
    @Test
    fun placeholderIncludesBlankAndLiteralTool() {
        assertTrue(ToolPartIdentity.isPlaceholder(null))
        assertTrue(ToolPartIdentity.isPlaceholder(""))
        assertTrue(ToolPartIdentity.isPlaceholder("tool"))
        assertTrue(ToolPartIdentity.isPlaceholder("TOOL"))
        assertFalse(ToolPartIdentity.isPlaceholder("question"))
        assertFalse(ToolPartIdentity.isPlaceholder("read"))
    }

    @Test
    fun coalesceKeepsKnownNameWhenIncomingIsPlaceholder() {
        assertEquals("question", ToolPartIdentity.coalesce("tool", "question"))
        assertEquals("question", ToolPartIdentity.coalesce("", "question"))
        assertEquals("bash", ToolPartIdentity.coalesce("bash", "question"))
        assertEquals("bash", ToolPartIdentity.coalesce("bash", "tool"))
    }

    @Test
    fun resolvedNameInfersQuestionOnlyWhenWireNameIsMissing() {
        val questions =
            JsonObject(
                mapOf("questions" to JsonArray(listOf(JsonObject(mapOf("header" to JsonPrimitive("A")))))),
            )
        assertEquals("question", ToolPartIdentity.resolvedName(null, questions))
        assertEquals("question", ToolPartIdentity.resolvedName("tool", questions))
        assertEquals("read", ToolPartIdentity.resolvedName("read", questions))
        assertEquals("", ToolPartIdentity.resolvedName(null, null))
        assertEquals(
            "read",
            ToolPartIdentity.resolvedName(
                null,
                JsonObject(mapOf("filePath" to JsonPrimitive("src/Main.kt"))),
            ),
        )
        assertEquals(
            "websearch",
            ToolPartIdentity.resolvedName(
                null,
                JsonObject(mapOf("query" to JsonPrimitive("opencode mobile"))),
            ),
        )
        assertEquals(
            "",
            ToolPartIdentity.resolvedName(
                null,
                JsonObject(mapOf("path" to JsonPrimitive("/home/example/Projects/session-test"))),
            ),
        )
    }

    @Test
    fun calledTitleDoesNotInventCalledTool() {
        assertEquals("Tool", ToolPartIdentity.calledTitle("tool"))
        assertEquals("Tool", ToolPartIdentity.calledTitle(""))
        assertEquals("Called read", ToolPartIdentity.calledTitle("read"))
    }

    @Test
    fun placeholderToolWithoutQuestionsIsHidden() {
        val part =
            MessagePart.Tool(
                toolName = "tool",
                status = ToolStatus.Running,
                id = "prt-1",
            )
        assertFalse(ToolPartIdentity.isVisibleOnTimeline(part))
        assertFalse(ToolPartIdentity.isVisibleOnTimeline(part.copy(status = ToolStatus.Completed)))
        assertTrue(
            ToolPartIdentity.isVisibleOnTimeline(
                MessagePart.Tool(toolName = "read", status = ToolStatus.Running, id = "prt-2"),
            ),
        )
        assertTrue(
            ToolPartIdentity.isVisibleOnTimeline(
                MessagePart.Tool(
                    toolName = "tool",
                    status = ToolStatus.Completed,
                    id = "prt-3",
                    input = JsonObject(mapOf("query" to JsonPrimitive("opencode"))),
                ),
            ),
        )
    }
}
