package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Shell, YAML, TOML, Dockerfile, SQL, and Ruby through the session: shared properties on seeded
 * input, streaming and edits across heredocs, block scalars, and multi-line strings, and the
 * worst cases. The timed randomized run covers them through [HighlightGenerators.languages].
 */
class ScriptingSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8721)
        for (language in ScriptingGenerators.languages) {
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
                "shellscript" to "x=\$(cat <<-'EOF'\n\tbody \$x\n\tEOF\n)\necho \"a \$(b \"c\")\n d\" 'e\nf' <<A\n\$y\nA\n",
                "yaml" to "a: |\n  one\n   two\nb: \"x\n  y\" # c\nc: {d: [1,\n 2]}\n- >-\n  t\n",
                "toml" to "[t]\na = \"\"\"\nx \\\n y\"\"\"\nb = [\n  {c = '''z\n'''},\n]\n",
                "docker" to "FROM a AS b\nRUN x \\\n  # skip\n  && echo \"\$V\" \\\n  && y\nRUN <<EOF\nz\nEOF\nCMD [\"a\"]\n",
                "sql" to "SELECT 'a\nb', \$f\$x\ny\$f\$, \"q\n\" /* c\n*/ FROM t;\n",
                "ruby" to "x = <<~E.strip\n  #{a}\n  E\ns = \"a #{b(\n1)} c\"\n=begin\nd\n=end\nw = %w(\n(e)\n)\n",
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
    fun openingAndClosingAHeredocReLexesOnlyWhatChanged() {
        val session = HighlightSession("shellscript")
        var spans = session.open(List(1_000) { "echo \"line $it\" | grep -v x" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

        // An unterminated heredoc turns every line below it into its body.
        val at = session.lineEnd(400)
        val open = session.replace(at, at, " <<END", never, noDeadline)
        assertEquals(400, open.startLine)
        assertEquals(session.lineCount, open.endLine)
        spans = mergeEdit(session, spans, open, 6)
        assertContentEquals(oneShot("shellscript", session.text()), spans)

        // Its terminator a few lines down turns everything below back into code.
        val close = session.lineEnd(403)
        val closed = session.replace(close, close, "\nEND", never, noDeadline)
        assertEquals(403, closed.startLine)
        assertEquals(session.lineCount, closed.endLine)
        spans = mergeEdit(session, spans, closed, 4)
        assertContentEquals(oneShot("shellscript", session.text()), spans)

        // Typing inside the body stops at the edited line.
        val inside = session.lineStart(402) + 3
        val typed = session.replace(inside, inside, "\$x \"", never, noDeadline)
        assertEquals(402, typed.startLine)
        assertEquals(403, typed.endLine)
        spans = mergeEdit(session, spans, typed, 4)
        assertContentEquals(oneShot("shellscript", session.text()), spans)
    }

    @Test
    fun editsInsideBlockScalarsAndMultiLineStringsStayLocal() {
        val cases =
            listOf(
                Triple("yaml", "key: |\n" + List(500) { "  text $it: \"q\"" }.joinToString("\n") + "\nnext: 1", "    deeper"),
                Triple("toml", "a = '''\n" + List(500) { "x$it = \"v\"" }.joinToString("\n") + "\n'''\nb = 1", "[t] # no"),
                Triple("ruby", "s = <<~EOS\n" + List(500) { "  line #{x$it} 'q'" }.joinToString("\n") + "\nEOS\ny = 1", "\"open"),
                Triple("sql", "SELECT \$\$\n" + List(500) { "x$it 'q' -- c" }.joinToString("\n") + "\n\$\$;", "/* open"),
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
    fun aLineFarBelowAnOpenHeredocIsBodyAfterAStatePass() {
        val text = "cat <<'EOF'\n" + List(20_000) { "line $it \$x" }.joinToString("\n") + "\nEOF\necho done"
        val session = HighlightSession("bash")
        session.open(text, 0, never, noDeadline)
        val inside = session.lineCount - 3
        val result = session.highlight(inside, session.lineCount, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(inside)
        val shifted = result.spans.toIntArray().also { ints -> for (index in ints.indices step 3) ints[index] -= from }
        assertEquals(
            "«String|line 19999 \$x»\n«Keyword|EOF»\n«Function|echo» done",
            markup(text.substring(from), HighlightSpans(shifted, result.spans.count)),
        )
    }

    @Test
    fun aLineLongerThanTheCapKeepsItsStateForTheNextLine() {
        // The cap cuts the line inside a double-quoted string, which stays open below it.
        val text = "echo \"" + "a".repeat(30_000) + "\"\necho done"
        val result = HighlightCore.highlight("shellscript", text, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        assertSpansValid(text, result.spans, HighlightLimits.standard)
        val last = result.spans.count - 1
        assertEquals(HighlightRole.String, result.spans.role(last))
        assertEquals(text.length, result.spans.end(last))
    }

    @Test
    fun theWorstCasesFinishFast() {
        val inputs =
            mapOf(
                "shellscript" to "echo \"" + "\$(\"\${x:-\$(".repeat(20_000),
                "yaml" to "k: \"" + "\\\" ".repeat(100_000) + "\n" + "- [{a: ".repeat(30_000),
                "toml" to "a = \"\"\"" + "\\\" \" ".repeat(60_000),
                "docker" to "RUN " + "echo \"\$(a \\\n".repeat(30_000),
                "sql" to "SELECT \$a\$" + "\$b\$ '' ".repeat(50_000),
                "ruby" to "s = \"" + "#{\"#{%w(".repeat(30_000) + "\n" + "x = <<~E\n  #{".repeat(10_000),
            )
        for ((language, text) in inputs) {
            val mark = TimeSource.Monotonic.markNow()
            val result = HighlightCore.highlight(language, text, never, HighlightDeadline.after(2_000.0))
            assertEquals(HighlightStatus.Complete, result.status, language)
            assertTrue(mark.elapsedNow() < 2.seconds, "$language took ${mark.elapsedNow()}")
        }
    }

    @Test
    fun aliasesResolve() {
        val expected =
            mapOf(
                "shellscript" to listOf("sh", "bash", "ZSH", "shell", "console", " ksh "),
                "yaml" to listOf("yml", "YAML"),
                "toml" to listOf("toml"),
                "docker" to listOf("dockerfile", "Containerfile"),
                "sql" to listOf("SQL"),
                "ruby" to listOf("rb", "gemspec", "rake"),
            )
        for ((id, aliases) in expected) {
            assertEquals(id, HighlightCore.canonicalLanguage(id))
            aliases.forEach { assertEquals(id, HighlightCore.canonicalLanguage(it), it) }
        }
    }
}
