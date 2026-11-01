package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.Color
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import org.junit.Assert.assertEquals
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

    @Test
    fun streamingNeverForcePlainUnderFormatAlways() {
        assertEquals(
            false,
            forcePlainStreamingCode(isStreaming = true, codeLength = 500),
        )
        assertEquals(
            false,
            forcePlainStreamingCode(isStreaming = false, codeLength = 5_000),
        )
        assertEquals(
            false,
            forcePlainStreamingCode(isStreaming = true, codeLength = 50_000),
        )
    }

    @Test
    fun variableOperatorAndPunctuationDrawInPlainCodeText() {
        val code = "count += 1;"
        val colors = testCodeColors()
        val spans =
            listOf(
                CodeSpan(0, 5, CodeTokenRole.Variable),
                CodeSpan(6, 8, CodeTokenRole.Operator),
                CodeSpan(10, 11, CodeTokenRole.Punctuation),
                CodeSpan(9, 10, CodeTokenRole.Number),
            )

        val result =
            buildCodeAnnotatedString(
                code = code,
                spans = spans,
                colors = colors,
            )

        assertEquals(colors.plain, result.colorAt(0))
        assertEquals(colors.plain, result.colorAt(6))
        assertEquals(colors.plain, result.colorAt(10))
        assertEquals(Color(0xFF0000AA), result.colorAt(9))
    }

    @Test
    fun propertyRoleUsesPropertySyntaxColor() {
        val result =
            buildCodeAnnotatedString(
                code = "this",
                spans = listOf(CodeSpan(0, 4, CodeTokenRole.Property)),
                colors = testCodeColors(),
            )

        assertEquals(Color.Red, result.colorAt(0))
    }

    private fun testCodeColors(): CodeColors =
        CodeColors.from(
            mapOf(
                "text-strong" to "#000000",
                "syntax-comment" to "#888888",
                "syntax-keyword" to "#0000FF",
                "syntax-string" to "#00FF00",
                "syntax-number" to "#0000AA",
                "syntax-constant" to "#00FFFF",
                "syntax-type" to "#FF00FF",
                "syntax-function" to "#FFFF00",
                "syntax-property" to "#FF0000",
                "syntax-attribute" to "#AA00AA",
            ),
        )

    private fun androidx.compose.ui.text.AnnotatedString.colorAt(index: Int): Color =
        spanStyles.single { range -> index in range.start until range.end }.item.color
}
