package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1033: projecting the timeline builds no per-line tool presentation and copies no tool body;
 * the detail is built from the tool part when it opens, with the content it had before.
 */
class ToolCardDeferredDetailTest {
    private val bigFile = (1..200_000).joinToString("\n") { "val line$it = $it" }
    private val bigOutput = (1..200_000).joinToString("\n") { "src/feature/File$it.kt" }

    private val write =
        MessagePart.Tool(
            toolName = "write",
            status = ToolStatus.Completed,
            id = "prt-write",
            input = json("filePath" to "src/Big.kt", "content" to bigFile),
            output = "Wrote file successfully.",
        )
    private val shell =
        MessagePart.Tool(
            toolName = "bash",
            status = ToolStatus.Completed,
            id = "prt-shell",
            input = json("command" to "find src"),
            output = bigOutput,
        )

    @Test
    fun projection_keepsOnlyTheCollapsedRow_notTheMultiMegabyteBodies() {
        assertTrue(bigFile.length > 3_000_000 && bigOutput.length > 4_000_000)
        val cards = cards(listOf(write, shell))

        assertEquals(2, cards.size)
        cards.forEach { card ->
            // The card holds no copy of the body and no list of lines: it prints small.
            assertTrue("card prints ${card.toString().length} chars", card.toString().length < 2_000)
            assertTrue(card.opensToolDetail)
            assertNull(card.detailText)
        }
        // The row still shows what it showed: the write's +N comes from counting, not splitting.
        assertEquals(200_000, cards[0].additions)
        assertEquals("find src", (cards[1].subtitle as UiText.Verbatim).value)
    }

    @Test
    fun detail_isBuiltOnOpen_withTheContentTheCardUsedToCarry() {
        val (writeCard, shellCard) = cards(listOf(write, shell))

        val writeDetail = SessionChatToolDetails.target(writeCard, write)!!
        val source = writeDetail.presentation as ToolResultPresentation.Source
        assertEquals(ToolResultInspector.fromTool(write, writeCard.title), source)
        assertEquals(200_000, source.lines.size)
        assertEquals("val line200000 = 200000", source.lines.last().text)
        assertEquals("Big.kt", (writeDetail.title as UiText.Verbatim).value)

        val shellDetail = SessionChatToolDetails.target(shellCard, shell)!!
        assertEquals(bigOutput, shellDetail.body)
        assertEquals(ToolResultInspector.fromTool(shell, shellCard.title), shellDetail.presentation)
        assertTrue(shellDetail.presentation is ToolResultPresentation.PathList)
    }

    @Test
    fun detail_isNullOnceTheToolPartIsGone() {
        val card = cards(listOf(shell)).single()
        assertNull(SessionChatToolDetails.target(card, tool = null))
        assertNull(SessionChatToolDetails.target(card, messages = emptyList()))
    }

    @Test
    fun detailRevision_followsAGrowingOutput() {
        val running = shell.copy(status = ToolStatus.Running, output = "a")
        val grown = running.copy(output = "a\nb")
        assertNotEquals(cards(listOf(running)).single(), cards(listOf(grown)).single())
        assertEquals(cards(listOf(grown)).single(), cards(listOf(grown.copy())).single())
    }

    @Test
    fun hasResult_matchesWhetherFromToolBuildsAPresentation() {
        val fixtures =
            listOf(
                tool("bash"),
                tool("bash", output = "   "),
                tool("bash", output = "\n  x"),
                tool("bash", status = ToolStatus.Error, error = "boom"),
                tool("bash", status = ToolStatus.Error, error = "  "),
                tool("read", output = "1: a"),
                tool("read", input = json("filePath" to "a.kt")),
                tool("write", input = json("filePath" to "a.kt", "content" to "x")),
                tool("write", input = json("filePath" to "  ", "content" to "x")),
                tool("write", input = json("content" to "x")),
                tool("write", input = json("filePath" to "a.kt", "content" to "")),
                tool("grep", input = json("pattern" to "x")),
                tool("edit", input = json("filePath" to "a.kt", "oldString" to "a", "newString" to "b")),
                tool("edit", input = json("filePath" to "a.kt", "oldString" to "a\n", "newString" to "a")),
                tool("edit", input = json("filePath" to "a.kt", "newString" to "\n")),
                tool("edit", input = json("filePath" to "a.kt", "oldString" to "a")),
                tool("edit", input = json("filePath" to "a.kt")),
                tool(
                    "apply_patch",
                    metadata = buildJsonObject { put("filediff", buildJsonObject { put("patch", "@@ -1 +1 @@\n-a\n+b") }) },
                ),
                tool("patch", metadata = buildJsonObject { put("filediff", buildJsonObject { put("patch", "no hunks") }) }),
                tool(
                    "apply_patch",
                    metadata = buildJsonObject { put("files", buildJsonArray { add(buildJsonObject { put("patch", "@@ x") }) }) },
                ),
                tool("webfetch"),
                tool("task", input = json("description" to "Look around")),
            )
        fixtures.forEach { fixture ->
            val built = ToolResultInspector.fromTool(fixture, UiText.verbatim("Title")) != null
            assertEquals(
                "${fixture.toolName} ${fixture.input} ${fixture.output} ${fixture.error}",
                built,
                ToolResultInspector.hasResult(fixture),
            )
        }
        assertFalse(ToolResultInspector.hasResult(tool("bash")))
    }

    private fun cards(parts: List<MessagePart>): List<SessionChatTimelineBlock.ToolCard> =
        (SessionChatTimelineBlocks.build(MessageRole.Assistant, parts) as SessionChatTimelineBlocksResult.Ok)
            .blocks
            .filterIsInstance<SessionChatTimelineBlock.ToolCard>()

    private fun tool(
        name: String,
        status: ToolStatus = ToolStatus.Completed,
        input: JsonObject? = null,
        output: String? = null,
        error: String? = null,
        metadata: JsonObject? = null,
    ): MessagePart.Tool =
        MessagePart.Tool(
            toolName = name,
            status = status,
            id = "prt-$name",
            input = input,
            output = output,
            error = error,
            metadata = metadata,
        )

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
}
