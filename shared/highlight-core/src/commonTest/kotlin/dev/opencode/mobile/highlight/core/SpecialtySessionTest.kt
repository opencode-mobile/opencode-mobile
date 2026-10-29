package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Vue, Svelte, GraphQL, MATLAB, and assembly through the session: the shared properties on
 * seeded input, streaming across embedded stretches, edits that open and close `<script>`,
 * `<style>`, interpolations, Svelte expressions, block strings, and block comments (re-lexing
 * still stops where states match), and bounded work on hostile lines.
 */
class SpecialtySessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8722)
        for (language in SpecialtyGenerators.languages) {
            repeat(150) { round ->
                val text = HighlightGenerators.text(language, random, random.nextInt(0, 600))
                val limits = if (round % 7 == 0) HighlightLimits(maxLineLength = random.nextInt(1, 30)) else HighlightLimits.standard
                HighlightPropertyChecks.checkAll(language, text, random, limits)
            }
        }
    }

    @Test
    fun idsAndAliasesResolve() {
        assertEquals("vue", HighlightCore.canonicalLanguage("Vue"))
        assertEquals("svelte", HighlightCore.canonicalLanguage("svelte"))
        assertEquals("graphql", HighlightCore.canonicalLanguage("gql"))
        assertEquals("matlab", HighlightCore.canonicalLanguage("MATLAB"))
        assertEquals("asm", HighlightCore.canonicalLanguage("assembly"))
        assertTrue(HighlightCore.languages.containsAll(SpecialtyGenerators.languages))
    }

    @Test
    fun aMarkdownFenceEmbedsAComponent() {
        assertGolden(
            "markdown",
            goldenLines("```vue", "<p :a=\"b\">{{ c }}</p>", "```", "```svelte", "{#if d}{e}{/if}", "```"),
            goldenLines(
                "«Punctuation|```»vue",
                "«Punctuation|<»«Tag|p» «Punctuation|:»«Attribute|a»«Punctuation|=\"»«Variable|b»«Punctuation|\">{{» «Variable|c» «Punctuation|}}</»«Tag|p»«Punctuation|>»",
                "«Punctuation|```»",
                "«Punctuation|```»svelte",
                "«Punctuation|{#»«Keyword|if» «Variable|d»«Punctuation|}{»«Variable|e»«Punctuation|}{/»«Keyword|if»«Punctuation|}»",
                "«Punctuation|```»",
            ),
        )
    }

    @Test
    fun streamingOneCharacterAtATimeMatchesOnePass() {
        val inputs =
            mapOf(
                "vue" to "<template>\n  <p :a=\"{\n b: 1 }\" @c='d(\n)'>{{ e\n }}</p>\n</template>\n<script lang=\"ts\">\nlet t = `a\n\${b}`;\n</script>\n<style>\na { color: red; /* x\n*/ }\n</style>\n",
                "svelte" to "<script>\nlet a = 1;\n</script>\n{#if a &&\n b}\n<p title=\"x {y\n}\" on:click={() => {\n go()\n}}>{z}</p>\n{/if}\n{#await p then v}{v}{/await}\n",
                "graphql" to "query Q(\$a: Int = 1) {\n  f(x: {y: \"z\"}) {\n    ...on T { g }\n  }\n}\n\"\"\"\nblock \\\"\"\"\n\"\"\"\ntype T { a: [B!]! }\n",
                "matlab" to "function [a, b] = f(x)\n%{\nblock\n%}\ny = [1 2 ...\n 3]';\ns = 'it''s'; hold on\nend\n",
                "asm" to "section .text\n_start: mov eax, 1 ; c\n/* block\n*/ movl \$1, %eax # x\nmsg db 'hi', 0\n",
            )
        for ((language, text) in inputs) {
            val expected = oneShot(language, text)
            val session = HighlightSession(language)
            var spans = session.open("", Int.MAX_VALUE, never, noDeadline).spans.toIntArray()
            for (char in text) {
                val before = session.lineCount
                val result = session.append(char.toString(), never, noDeadline)
                assertEquals(HighlightStatus.Complete, result.status)
                assertTrue(result.startLine >= before - 1)
                spans = mergeEdit(session, spans, result, 1)
            }
            assertContentEquals(expected, spans, "$language streamed ${describe(spans)}\none-shot ${describe(expected)}")
        }
    }

    private val markupLines = List(1_000) { "<p class=\"c$it\">text $it &amp; more</p>" }

    @Test
    fun openingAndClosingAVueScriptReLexesOnlyWhatChanged() {
        checkOpenAndClose("vue", markupLines, open = "<script lang=\"ts\">", close = "</script>")
    }

    @Test
    fun openingAndClosingAVueStyleReLexesOnlyWhatChanged() {
        checkOpenAndClose("vue", markupLines, open = "<style>", close = "</style>")
    }

    @Test
    fun openingAndClosingAVueInterpolationReLexesOnlyWhatChanged() {
        checkOpenAndClose("vue", markupLines, open = "{{", close = "}}")
    }

    @Test
    fun openingAndClosingASvelteBlockTagReLexesOnlyWhatChanged() {
        checkOpenAndClose("svelte", markupLines, open = "{#if ready", close = "}")
    }

    @Test
    fun openingAndClosingASvelteScriptReLexesOnlyWhatChanged() {
        checkOpenAndClose("svelte", markupLines, open = "<script>", close = "</script>")
    }

    @Test
    fun openingAndClosingAGraphqlBlockStringReLexesOnlyWhatChanged() {
        checkOpenAndClose("graphql", List(1_000) { "field$it(first: $it) { id name }" }, open = "\"\"\"", close = "\"\"\"")
    }

    @Test
    fun openingAndClosingAMatlabBlockCommentReLexesOnlyWhatChanged() {
        checkOpenAndClose("matlab", List(1_000) { "x$it = foo(a, b) + c';" }, open = "%{", close = "%}", closeOnOwnLine = true)
    }

    @Test
    fun openingAndClosingAnAssemblyBlockCommentReLexesOnlyWhatChanged() {
        checkOpenAndClose("asm", List(1_000) { "    mov eax, $it ; c" }, open = "/*", close = "*/")
    }

    /**
     * Opening [open] at line 400 changes every line below; closing it a few lines down changes
     * every line below back; typing inside the open stretch, and a closed stretch inserted above,
     * stop at the edited lines because the end states match again.
     */
    private fun checkOpenAndClose(
        language: String,
        lines: List<String>,
        open: String,
        close: String,
        closeOnOwnLine: Boolean = false,
    ) {
        val session = HighlightSession(language)
        var spans = session.open(lines.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

        val at = session.lineStart(400)
        val opened = session.replace(at, at, "$open\n", never, noDeadline)
        assertEquals(400, opened.startLine)
        assertEquals(session.lineCount, opened.endLine)
        spans = mergeEdit(session, spans, opened, open.length + 1)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after opening")

        val closeAt = session.lineEnd(403)
        val closeText = if (closeOnOwnLine) "\n$close" else close
        val closed = session.replace(closeAt, closeAt, closeText, never, noDeadline)
        assertEquals(403, closed.startLine)
        assertEquals(session.lineCount, closed.endLine)
        spans = mergeEdit(session, spans, closed, closeText.length)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after closing")

        val inside = session.lineStart(402) + 1
        val typed = session.replace(inside, inside, "x1 ", never, noDeadline)
        assertEquals(402, typed.startLine)
        assertEquals(403, typed.endLine)
        spans = mergeEdit(session, spans, typed, 3)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after typing inside")

        val above = session.lineStart(100)
        val block = "$open\nlet y = 2;\n$close\n"
        val inserted = session.replace(above, above, block, never, noDeadline)
        assertEquals(100, inserted.startLine)
        // The three new lines and the edit's last line, which ends as before.
        assertEquals(104, inserted.endLine)
        spans = mergeEdit(session, spans, inserted, block.length)
        assertContentEquals(oneShot(language, session.text()), spans, "$language after a closed block above")
    }

    @Test
    fun hostileLongLinesFinishFast() {
        val limits = HighlightLimits(maxLineLength = 200_000)
        val inputs =
            listOf(
                "vue" to "{{ ".repeat(40_000),
                "vue" to "<a :b=\"".repeat(20_000),
                "vue" to "<div v-if=\"a\" :b=\"c\" @d.e=\"f\">{{ g }}</div>".repeat(3_000),
                "vue" to "<script>" + "x".repeat(100_000),
                "svelte" to "{".repeat(100_000),
                "svelte" to "}".repeat(100_000),
                "svelte" to "{#await a then b ".repeat(10_000),
                "svelte" to "<p title=\"" + "a {b} ".repeat(20_000),
                "svelte" to "{'".repeat(50_000),
                "svelte" to "<a on:click|x|y={z} {w}>".repeat(5_000),
                "graphql" to "{".repeat(100_000),
                "graphql" to "(a: ".repeat(30_000),
                "graphql" to "\"\\u".repeat(40_000),
                "graphql" to "\"\"\"".repeat(40_000),
                "graphql" to "a" + " ".repeat(100_000) + "b",
                "matlab" to "'".repeat(100_000),
                "matlab" to "a'".repeat(50_000),
                "matlab" to "[".repeat(100_000),
                "matlab" to "function [".repeat(10_000),
                "matlab" to "hold " + "on ".repeat(30_000),
                "matlab" to "properties" + " ".repeat(100_000) + "x",
                "asm" to " ".repeat(50_000) + "x" + " #1".repeat(30_000),
                "asm" to "a:".repeat(50_000),
                "asm" to "\"\\".repeat(50_000),
                "asm" to "mov " + "%eax, ".repeat(20_000),
                "asm" to "x" + " ".repeat(100_000) + "db",
            )
        for ((language, text) in inputs) {
            val mark = TimeSource.Monotonic.markNow()
            val result = HighlightCore.highlight(language, text, never, HighlightDeadline.after(2_000.0), limits)
            assertEquals(HighlightStatus.Complete, result.status, "$language ${text.take(12)}")
            assertSpansValid(text, result.spans, limits)
            assertTrue(mark.elapsedNow() < 2.seconds, "$language ${text.take(12)} took ${mark.elapsedNow()}")
        }
    }
}
