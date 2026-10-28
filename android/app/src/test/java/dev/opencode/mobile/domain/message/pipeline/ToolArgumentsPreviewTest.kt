package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsPreviewTest {
    @Test
    fun partialStringFields_readsAStringStillBeingWritten() {
        assertEquals(emptyMap<String, String>(), fields(""))
        assertEquals(emptyMap<String, String>(), fields("{"))
        assertEquals(emptyMap<String, String>(), fields("""{"comm"""))
        assertEquals(emptyMap<String, String>(), fields("""{"command""""))
        assertEquals(mapOf("command" to ""), fields("""{"command": """"))
        assertEquals(mapOf("command" to "ls -la /t"), fields("""{"command": "ls -la /t"""))
        assertEquals(
            mapOf("command" to "ls", "description" to "Lis"),
            fields("""{"command":"ls","timeout":12000,"description":"Lis"""),
        )
    }

    @Test
    fun partialStringFields_decodesEscapesAndDropsAnUnfinishedOne() {
        assertEquals(mapOf("content" to "a\n\"b\""), fields("""{"content":"a\n\"b\"""""))
        assertEquals(mapOf("content" to "tab"), fields("""{"content":"tab\"""))
        assertEquals(mapOf("content" to "é"), fields("""{"content":"é"""))
        assertEquals(mapOf("content" to "x"), fields("""{"content":"x\u00"""))
    }

    @Test
    fun partialStringFields_skipsNestedValuesAndNeverThrowsOnGarbage() {
        assertEquals(
            mapOf("filePath" to "a.ts", "newString" to "b"),
            fields("""{"edits":[{"old":"}"},{"x":[1,2]}],"filePath":"a.ts","newString":"b"""),
        )
        assertEquals(emptyMap<String, String>(), fields("not json"))
        assertEquals(emptyMap<String, String>(), fields("""["command"]"""))
        assertEquals(mapOf("a" to "1"), fields("""{"a":"1"} trailing"""))
        assertEquals(emptyMap<String, String>(), fields("""{"a":"""))
    }

    @Test
    fun raw_isOnlyThePreviewOfAPendingOrRunningToolWithoutSettledArguments() {
        val preview = JsonObject(mapOf("preview" to JsonPrimitive("""{"command":"ls""")))
        assertEquals("""{"command":"ls""", ToolArgumentsPreview.raw(tool(ToolStatus.Pending, preview)))
        assertEquals("ls", ToolArgumentsPreview.string(tool(ToolStatus.Running, preview), "command"))
        assertNull(ToolArgumentsPreview.raw(tool(ToolStatus.Completed, preview)))
        assertNull(ToolArgumentsPreview.raw(tool(ToolStatus.Error, preview)))
        assertNull(ToolArgumentsPreview.raw(tool(ToolStatus.Pending, null)))
        val settled = JsonObject(preview + ("command" to JsonPrimitive("ls -la")))
        assertFalse(ToolArgumentsPreview.isReceiving(tool(ToolStatus.Pending, settled)))
        assertTrue(ToolArgumentsPreview.isReceiving(tool(ToolStatus.Pending, preview)))
    }

    @Test
    fun mergedInput_settledArgumentsDropThePreview() {
        val preview = JsonObject(mapOf("preview" to JsonPrimitive("""{"command":"l""")))
        val settled = JsonObject(mapOf("command" to JsonPrimitive("ls")))
        assertEquals(settled, ToolArgumentsPreview.mergedInput(preview, settled))
        val longer = JsonObject(mapOf("preview" to JsonPrimitive("""{"command":"ls"}""")))
        assertEquals(longer, ToolArgumentsPreview.mergedInput(preview, longer))
        assertEquals(preview, ToolArgumentsPreview.mergedInput(preview, JsonObject(emptyMap())))
    }

    private fun fields(raw: String): Map<String, String> = ToolArgumentsPreview.partialStringFields(raw)

    private fun tool(
        status: ToolStatus,
        input: JsonObject?,
    ): MessagePart.Tool = MessagePart.Tool(toolName = "bash", status = status, input = input, id = "call_1")
}
