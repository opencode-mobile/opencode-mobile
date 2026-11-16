package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeStructureOnlyHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown a cut user message renders (#1043): markers its cut line leaves open are closed
 * before the "…". Same vectors as iOS `MessageTextCutMarkdownTests`.
 */
class MessageTextCutMarkdownTest {
    /** A shown prefix, what the parser is given for it, and how that renders with the "…". */
    private class Vector(
        val prefix: String,
        val repaired: String,
        val rendered: String,
    )

    private val vectors =
        listOf(
            Vector("Plain and **bold wor", "Plain and **bold wor**", "Plain and <b>bold wor</b>…"),
            Vector("Plain and *italic wor", "Plain and *italic wor*", "Plain and <i>italic wor</i>…"),
            Vector("Plain and _italic wor", "Plain and _italic wor_", "Plain and <i>italic wor</i>…"),
            // The parser has no strikethrough: the whole message shows its tildes too.
            Vector("Plain and ~~struck wor", "Plain and ~~struck wor", "Plain and ~~struck wor…"),
            Vector("Run `git log --one", "Run `git log --one`", "Run <code>git log --one</code>…"),
            Vector("See [the docs", "See the docs", "See the docs…"),
            Vector("See [the docs](https://example.com/pa", "See the docs", "See the docs…"),
            Vector("Look ![a diag", "Look ![a diag]()", "Look <img a diag>…"),
            Vector("Look ![a diagram](https://example.com/a.p", "Look ![a diagram](https://example.com/a.p)", "Look <img a diagram>…"),
            Vector("_italic and **bold wor", "_italic and **bold wor**_", "<i>italic and <b>bold wor</b></i>…"),
            // `**bold *it***` would close the bold early, so the inner opener is dropped.
            Vector("**bold and *it", "**bold and it**", "<b>bold and it</b>…"),
            Vector("Some **bold** and more wor", "Some **bold** and more wor", "Some <b>bold</b> and more wor…"),
            Vector(
                "See [docs](https://example.com) and **mo",
                "See [docs](https://example.com) and **mo**",
                "See <a https://example.com>docs</a> and <b>mo</b>…",
            ),
            Vector("Price \\*5 and more", "Price \\*5 and more", "Price \\*5 and more…"),
            Vector("Run `a **b` and more wor", "Run `a **b` and more wor", "Run <code>a **b</code> and more wor…"),
            Vector("call some_function and mo", "call some_function and mo", "call some_function and mo…"),
            Vector("Some text **", "Some text", "Some text…"),
            Vector("- one\n- item with **bold wor", "- one\n- item with **bold wor**", "- one\n- item with <b>bold wor</b>…"),
            Vector("## Title with *emph", "## Title with *emph*", "## Title with <i>emph</i>…"),
            Vector("Look:\n\n```\nlet **x = 1\nlet *y", "Look:\n\n```\nlet **x = 1\nlet *y", "Look:\n```let **x = 1\nlet *y…```"),
        )

    @Test
    fun cutLine_closesTheMarkersItLeftOpen() {
        vectors.forEach { vector ->
            val repaired = OpenCodeMarkdownParser.closeMarkersLeftOpen(vector.prefix)
            assertEquals(vector.prefix, vector.repaired, repaired)
            assertEquals(vector.prefix, vector.rendered, render(repaired + "…"))
        }
    }

    @Test
    fun cutInsideBold_rendersBoldToTheCut() {
        val text = (1..20).joinToString("\n") { "line $it" } + "\nStart of the long line **" + "abcdef ".repeat(90) + "** end"
        val cut = MessageTextLimit.cut(text)!!
        val message = Message(id = "m", role = MessageRole.User, text = text, sentAtEpochMs = 1)

        assertTrue(cut.endsMidLine)
        assertFalse(cut.visibleText.endsWith("**…"))
        assertEquals(cut.visibleText.dropLast(1) + "**…", message.markdownSourceText)
        val paragraph = parse(message.markdownSourceText).blocks.single() as MarkdownBlock.Paragraph
        assertTrue(paragraph.inlines.any { it is MarkdownInline.Strong })
        assertEquals(MarkdownInline.Text("…"), paragraph.inlines.last())
    }

    @Test
    fun cutAtALineBreak_isGivenToTheParserAsShown() {
        val text = (1..40).joinToString("\n") { "line **$it" }
        val cut = MessageTextLimit.cut(text)!!

        assertFalse(cut.endsMidLine)
        assertEquals(cut.visibleText, cut.markdownSource)
    }

    @Test
    fun assistantMessage_isParsedWhole() {
        val text = "Some **bold " + "abcdef ".repeat(400)
        val message = Message(id = "m", role = MessageRole.Assistant, text = text, sentAtEpochMs = 1)
        assertEquals(text, message.markdownSourceText)
    }

    private fun parse(markdown: String) =
        OpenCodeMarkdownParser.parse(markdown = markdown, codeHighlighter = OpenCodeStructureOnlyHighlighter)

    /** The parsed blocks of [markdown] in a compact notation both platforms' tests share. */
    private fun render(markdown: String): String =
        parse(markdown).blocks.joinToString("\n") { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> inlines(block.inlines)
                is MarkdownBlock.Heading -> "#".repeat(block.level) + " " + inlines(block.inlines)
                is MarkdownBlock.BulletList -> block.items.joinToString("\n") { "- " + inlines(it.inlines) }
                is MarkdownBlock.CodeBlock -> "```" + block.code + "```"
                else -> block.toString()
            }
        }

    private fun inlines(inlines: List<MarkdownInline>): String =
        inlines.joinToString("") { inline ->
            when (inline) {
                is MarkdownInline.Text -> inline.text
                is MarkdownInline.Strong -> "<b>" + inlines(inline.children) + "</b>"
                is MarkdownInline.Emphasis -> "<i>" + inlines(inline.children) + "</i>"
                is MarkdownInline.Code -> "<code>" + inline.code + "</code>"
                is MarkdownInline.Link -> "<a " + inline.destination + ">" + inlines(inline.children) + "</a>"
                is MarkdownInline.ImageAlt -> "<img " + inline.alt + ">"
                MarkdownInline.SoftBreak, MarkdownInline.HardBreak -> "\n"
            }
        }
}
