package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Julia, Crystal, Nim, CoffeeScript, Elixir, and Erlang through the session: shared properties
 * on seeded input, streaming and edits across multi-line strings, block comments, heredocs and
 * interpolations, and the worst cases. The timed randomized run covers them through
 * [HighlightGenerators.languages].
 */
class LiteralStackSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8726)
        for (language in LiteralStackGenerators.languages) {
            repeat(150) { round ->
                val text = HighlightGenerators.text(language, random, random.nextInt(0, 500))
                val limits = if (round % 7 == 0) HighlightLimits(maxLineLength = random.nextInt(1, 30)) else HighlightLimits.standard
                HighlightPropertyChecks.checkAll(language, text, random, limits)
            }
        }
    }

    @Test
    fun streamingOneCharacterAtATimeMatchesOnePass() {
        val samples =
            mapOf(
                "julia" to "s = \"\"\"\na \$x \$(f(\n1)) \"q\"\n\"\"\"\n#= a #= b\n=# c =# x'\nr\"\"\"\nraw \$y\n\"\"\"i\n",
                "crystal" to "x = <<-EOS\n  #{a}\n  EOS\ns = \"a #{b(\n1)} c\"\nw = %w(\n(e)\n)\n{% if x %}\n{{ y }}\n",
                "nim" to "#[ a #[ b\n]# c ]# let s = \"\"\"\nx\"\n\"\"\"\" & r\"q\"\"\" {.push,\n x.}\n",
                "coffee" to "###\nblock\n###\ns = \"a #{b \"c\n#{d}\"} e\"\nr = ///\n x # c\n #{y}\n///g\nf = (a) ->\n",
                "elixir" to "@doc \"\"\"\n#{a}\n\"\"\"\ns = ~S(a (b\n) c)\nw = ~w[\nx]a\nm = :\"q #{\n1}\"\n",
                "erlang" to "-module(m).\nS = \"a ~p\nb\",\nT = \"\"\"\n x\n \"\"\",\n'at\nom'.\n",
            )
        for ((language, text) in samples) {
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

    @Test
    fun openingAndClosingABlockCommentReLexesOnlyWhatChanged() {
        val session = HighlightSession("julia")
        var spans = session.open(List(1_000) { "x$it = f(\"line \$it\")" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

        // An unclosed `#=` turns every line below it into comment.
        val at = session.lineEnd(400)
        val open = session.replace(at, at, " #=", never, noDeadline)
        assertEquals(400, open.startLine)
        assertEquals(session.lineCount, open.endLine)
        spans = mergeEdit(session, spans, open, 3)
        assertContentEquals(oneShot("julia", session.text()), spans)

        // Its `=#` a few lines down turns everything below back into code.
        val close = session.lineEnd(403)
        val closed = session.replace(close, close, " =#", never, noDeadline)
        assertEquals(403, closed.startLine)
        assertEquals(session.lineCount, closed.endLine)
        spans = mergeEdit(session, spans, closed, 3)
        assertContentEquals(oneShot("julia", session.text()), spans)

        // Typing inside the comment stops at the edited line.
        val inside = session.lineStart(402) + 3
        val typed = session.replace(inside, inside, "\"q", never, noDeadline)
        assertEquals(402, typed.startLine)
        assertEquals(403, typed.endLine)
        spans = mergeEdit(session, spans, typed, 2)
        assertContentEquals(oneShot("julia", session.text()), spans)
    }

    @Test
    fun editsInsideMultiLineConstructsStayLocal() {
        val cases =
            listOf(
                Triple("julia", "s = \"\"\"\n" + List(500) { "text $it 'q' #= x" }.joinToString("\n") + "\n\"\"\"\ny = 1", "\"open"),
                Triple("crystal", "s = <<-EOS\n" + List(500) { "  line #{x$it} 'q'" }.joinToString("\n") + "\nEOS\ny = 1", "\"open"),
                Triple("nim", "#[\n" + List(500) { "x$it = \"v\" #[ ]#" }.joinToString("\n") + "\n]#\nlet b = 1", "\"\"\""),
                Triple("coffee", "###\n" + List(500) { "x$it = \"v\" ## #{x}" }.joinToString("\n") + "\n###\nb = 1", "'''"),
                Triple("elixir", "@doc ~S\"\"\"\n" + List(500) { "x$it = \"v\" #{y}" }.joinToString("\n") + "\n\"\"\"\nb = 1", "'open"),
                Triple("erlang", "S = \"\n" + List(500) { "x$it 'q' % c" }.joinToString("\n") + "\n\",\nB = 1", "'x' ~p %"),
            )
        for ((language, text, insert) in cases) {
            val session = HighlightSession(language)
            val old = session.open(text, Int.MAX_VALUE, never, noDeadline).spans.toIntArray()
            val at = session.lineStart(250)
            val result = session.replace(at, at, insert, never, noDeadline)
            assertEquals(250, result.startLine, language)
            assertEquals(251, result.endLine, language)
            assertContentEquals(oneShot(language, session.text()), mergeEdit(session, old, result, insert.length), language)
        }
    }

    @Test
    fun aLineFarBelowAnOpenStringIsStringAfterAStatePass() {
        val text = "s = \"\"\"\n" + List(20_000) { "line $it" }.joinToString("\n") + "\n\"\"\"\nputs(s)"
        val session = HighlightSession("julia")
        session.open(text, 0, never, noDeadline)
        val inside = session.lineCount - 3
        val result = session.highlight(inside, session.lineCount, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(inside)
        val shifted = result.spans.toIntArray().also { ints -> for (index in ints.indices step 3) ints[index] -= from }
        assertEquals(
            "«String|line 19999»\n«String|\"\"\"»\n«Function|puts»«Punctuation|(»s«Punctuation|)»",
            markup(text.substring(from), HighlightSpans(shifted, result.spans.count)),
        )
    }

    @Test
    fun aLineLongerThanTheCapKeepsItsStateForTheNextLine() {
        // The cap cuts each first line inside a literal; only Nim's one-line string ends with its line.
        val cases =
            listOf(
                Triple("julia", "s = \"", HighlightRole.String),
                Triple("crystal", "s = %w(", HighlightRole.String),
                Triple("nim", "let s = \"", HighlightRole.Function),
                Triple("coffee", "### ", HighlightRole.Comment),
                Triple("elixir", "s = ~S\"\"\"", HighlightRole.String),
                Triple("erlang", "S = \"", HighlightRole.String),
            )
        for ((language, opener, role) in cases) {
            val text = opener + "a".repeat(30_000) + "\necho"
            val result = HighlightCore.highlight(language, text, never, noDeadline)
            assertEquals(HighlightStatus.Complete, result.status, language)
            assertSpansValid(text, result.spans, HighlightLimits.standard)
            val last = result.spans.count - 1
            assertEquals(role, result.spans.role(last), language)
            assertEquals(text.length, result.spans.end(last), language)
        }
    }

    @Test
    fun theWorstCasesFinishFast() {
        val inputs =
            mapOf(
                "julia" to "s = \"" + "\$(\"\$(#=".repeat(20_000) + "\n" + "'".repeat(50_000) + "\n" + "#=".repeat(30_000),
                "crystal" to "s = \"" + "#{\"#{%w(".repeat(30_000) + "\n" + "x = <<-E\n  #{".repeat(10_000),
                "nim" to "#[" + "#[".repeat(30_000) + "\n" + "{.".repeat(30_000) + "\n" + "`".repeat(50_000),
                "coffee" to "f = (" + "a = (".repeat(20_000) + "\n" + "/".repeat(50_000) + "\n" + "\"#{\"#{".repeat(20_000),
                "elixir" to "~s(" + "((((#{~s(".repeat(20_000) + "\n" + "?".repeat(50_000),
                "erlang" to "\"" + "~".repeat(60_000) + "\n" + "\$\\x{".repeat(20_000) + "\n" + "-".repeat(50_000),
            )
        for ((language, text) in inputs) {
            val mark = TimeSource.Monotonic.markNow()
            val result = HighlightCore.highlight(language, text, never, HighlightDeadline.after(2_000.0))
            assertEquals(HighlightStatus.Complete, result.status, language)
            assertSpansValid(text, result.spans, HighlightLimits.standard)
            assertTrue(mark.elapsedNow() < 2.seconds, "$language took ${mark.elapsedNow()}")
        }
    }

    @Test
    fun aliasesResolve() {
        val expected =
            mapOf(
                "julia" to listOf("jl", "Julia"),
                "crystal" to listOf("Crystal"),
                "nim" to listOf("NIM"),
                "coffee" to listOf("coffeescript", "CoffeeScript"),
                "elixir" to listOf(" elixir "),
                "erlang" to listOf("erl"),
            )
        for ((id, aliases) in expected) {
            assertEquals(id, HighlightCore.canonicalLanguage(id))
            aliases.forEach { assertEquals(id, HighlightCore.canonicalLanguage(it), it) }
        }
    }
}
