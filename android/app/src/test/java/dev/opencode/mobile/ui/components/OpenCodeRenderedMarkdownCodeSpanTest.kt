package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.Color
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRenderedMarkdownCodeSpanTest {
    @Test
    fun duplicateOverlappingKeywordSpansRenderConstOnce() {
        val code = "const x"
        val spans =
            listOf(
                CodeSpan(0, 5, CodeTokenRole.Keyword),
                CodeSpan(0, 5, CodeTokenRole.Keyword),
            )

        val result =
            buildCodeAnnotatedString(
                code = code,
                spans = spans,
                colors = testCodeColors(),
            )

        assertEquals("const x", result.text)
    }

    @Test
    fun overlappingHttpClientSpansColorEntireIdentifier() {
        val code = "HttpClient"
        val typeColor = Color.Magenta
        val spans =
            listOf(
                CodeSpan(3, 4, CodeTokenRole.Property),
                CodeSpan(5, 6, CodeTokenRole.Property),
                CodeSpan(0, 10, CodeTokenRole.Type),
            )

        val result =
            buildCodeAnnotatedString(
                code = code,
                spans = spans,
                colors = testCodeColors(),
            )

        assertEquals(code, result.text)
        val typeCoverage =
            result.spanStyles
                .filter { it.item.color == typeColor }
                .sumOf { range -> range.end - range.start }
        assertEquals(code.length, typeCoverage)
    }

    @Test
    fun overlappingPatchPathSpansPreserveSourceText() {
        val code = "patch(path"
        val spans =
            listOf(
                CodeSpan(0, 5, CodeTokenRole.Function),
                CodeSpan(4, 5, CodeTokenRole.Type),
                CodeSpan(6, 10, CodeTokenRole.Variable),
                CodeSpan(8, 10, CodeTokenRole.Type),
            )

        val result =
            buildCodeAnnotatedString(
                code = code,
                spans = spans,
                colors = testCodeColors(),
            )

        assertEquals(code, result.text)
    }

    private fun testCodeColors(): CodeColors =
        CodeColors(
            plain = Color.Black,
            comment = Color.Gray,
            keyword = Color.Blue,
            string = Color.Green,
            constant = Color.Cyan,
            type = Color.Magenta,
            property = Color.Red,
            primitive = Color.Yellow,
            info = Color.DarkGray,
            inserted = Color.Green,
            deleted = Color.Red,
            warning = Color.Yellow,
            critical = Color.Red,
        )
}
