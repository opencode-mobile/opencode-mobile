package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Lua, Perl, PowerShell, AWK, Make, and R through the session: shared properties on seeded
 * input, streaming and edits across long strings, heredocs, here-strings, POD, `define` bodies,
 * and continued lines, and the worst cases. The timed randomized run covers them through
 * [HighlightGenerators.languages].
 */
class GlueSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8726)
        for (language in GlueGenerators.languages) {
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
                "lua" to "local s = [==[\na ]] b\n]==] .. --[[ c\nd ]] \"e \\z\n f\"\nprint(s)\n",
                "perl" to "my \$s = s{a\n}\n{b}g;\nprint <<~EOT, q(x\n(y));\n  hi \$name\n  EOT\n\n=pod\n\$x\n=cut\n__END__\nz\n",
                "powershell" to "\$t = @\"\nHi \$(Get-Date)\n\"@\n\$s = \"a \$(\n\"b\") c\"\n<# x\n#> 'q\nr'\n",
                "awk" to "BEGIN { s = \"a\\\nb\"; x = /re/ }\n\$1 ~ /x[/]/ { print \$NF }\n",
                "make" to "X = a \\\n  \$(sh \\\n b)\nall: x\n\techo \"a \\\n b\" && \\\n  ls\ndefine B\n\$(X)\nendef\n# c \\\nd\n",
                "r" to "s <- \"a\nb\"\nr <- r\"--(x\n)-\"\n)--\"\nf <- function(x) x\n",
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
    fun openingAndClosingAMultiLineConstructReLexesOnlyWhatChanged() {
        val cases =
            listOf(
                Triple("lua", " --[==[", "]==]"),
                Triple("perl", " <<\"END\";", "END"),
                Triple("powershell", " <#", "#>"),
                Triple("r", " r\"(", ")\""),
            )
        for ((language, opener, closer) in cases) {
            val session = HighlightSession(language)
            var spans = session.open(List(1_000) { "x$it = f(\"line $it\")" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

            // An unterminated construct turns every line below it into its body.
            val at = session.lineEnd(400)
            val open = session.replace(at, at, opener, never, noDeadline)
            assertEquals(400, open.startLine, language)
            assertEquals(session.lineCount, open.endLine, language)
            spans = mergeEdit(session, spans, open, opener.length)
            assertContentEquals(oneShot(language, session.text()), spans, language)

            // Its closer a few lines down turns everything below back into code.
            val close = session.lineEnd(403)
            val closed = session.replace(close, close, "\n$closer", never, noDeadline)
            assertEquals(403, closed.startLine, language)
            assertEquals(session.lineCount, closed.endLine, language)
            spans = mergeEdit(session, spans, closed, closer.length + 1)
            assertContentEquals(oneShot(language, session.text()), spans, language)

            // Typing inside the body stops at the edited line.
            val inside = session.lineStart(402) + 3
            val typed = session.replace(inside, inside, "\$x \"", never, noDeadline)
            assertEquals(402, typed.startLine, language)
            assertEquals(403, typed.endLine, language)
            spans = mergeEdit(session, spans, typed, 4)
            assertContentEquals(oneShot(language, session.text()), spans, language)
        }
    }

    @Test
    fun editsInsideMultiLineBodiesStayLocal() {
        val cases =
            listOf(
                Triple("lua", "s = [[\n" + List(500) { "x$it = 'q' -- c" }.joinToString("\n") + "\n]]\ny = 1", "\"open"),
                Triple("perl", "print <<EOF;\n" + List(500) { "line \$x$it 'q'" }.joinToString("\n") + "\nEOF\n\$y = 1;", "\"open"),
                Triple("perl", "=pod\n" + List(500) { "doc $it \"q\"" }.joinToString("\n") + "\n=cut\n\$y = 1;", "=head1 x"),
                Triple("powershell", "\$t = @'\n" + List(500) { "x$it \"q\"" }.joinToString("\n") + "\n'@\n\$y = 1", "\$(open"),
                Triple("make", "define B\n" + List(500) { "x$it = \$(Y) # c" }.joinToString("\n") + "\nendef\ny = 1", "\tz: \\"),
                Triple("r", "s <- \"\n" + List(500) { "x$it <- 'q' # c" }.joinToString("\n") + "\n\"\ny <- 1", "r'(open"),
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
    fun aLineFarBelowAnOpenLongStringIsBodyAfterAStatePass() {
        val text = "local s = [==[\n" + List(20_000) { "line $it ]] \"x\"" }.joinToString("\n") + "\n]==] .. x\nprint(s)"
        val session = HighlightSession("lua")
        session.open(text, 0, never, noDeadline)
        val inside = session.lineCount - 3
        val result = session.highlight(inside, session.lineCount, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(inside)
        val shifted = result.spans.toIntArray().also { ints -> for (index in ints.indices step 3) ints[index] -= from }
        assertEquals(
            "«String|line 19999 ]] \"x\"»\n«String|]==]» «Operator|..» x\n«Function|print»«Punctuation|(»s«Punctuation|)»",
            markup(text.substring(from), HighlightSpans(shifted, result.spans.count)),
        )
    }

    @Test
    fun aLineLongerThanTheCapKeepsItsStateForTheNextLine() {
        // The cap cuts the line inside a string, which stays open below it.
        for ((language, open) in listOf("r" to "s <- \"", "powershell" to "\$s = '", "perl" to "my \$s = q{")) {
            val text = open + "a".repeat(30_000) + "\nmore\nx"
            val result = HighlightCore.highlight(language, text, never, noDeadline)
            assertEquals(HighlightStatus.Complete, result.status, language)
            assertSpansValid(text, result.spans, HighlightLimits.standard)
            val second = text.indexOf("more")
            val covering = (0 until result.spans.count).firstOrNull { result.spans.start(it) == second }
            assertTrue(covering != null && result.spans.role(covering) == HighlightRole.String, language)
        }
    }

    @Test
    fun theWorstCasesFinishFast() {
        val inputs =
            mapOf(
                "lua" to "s = \"" + "\\z [=[ ]=] ".repeat(40_000) + "\n" + "--[==[ ]=] ".repeat(40_000),
                "perl" to "s{" + "\${\$x}{y}@{".repeat(30_000) + "\n" + "<<EOF " + "qq{ s( m[".repeat(20_000),
                "powershell" to "\"" + "\$(\"\${\$(@\"".repeat(30_000) + "\n" + "@\"\n" + "\$(\$(\$(".repeat(30_000),
                "awk" to "/" + "[\\/[^]/".repeat(40_000) + "\n" + "\"\\".repeat(60_000),
                "make" to "X = " + "\$(\$(\${(".repeat(30_000) + "\n\t" + "\"\$\$(\$(".repeat(30_000),
                "r" to "x <- r\"" + "---(".repeat(40_000) + "\n" + "%".repeat(60_000) + "\n" + ")--\"".repeat(40_000),
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
                "lua" to listOf("LUA"),
                "perl" to listOf("Perl"),
                "powershell" to listOf("ps", "ps1", "pwsh", "PowerShell"),
                "awk" to listOf("AWK"),
                "make" to listOf("makefile", "Makefile"),
                "r" to listOf("R"),
            )
        for ((id, aliases) in expected) {
            assertEquals(id, HighlightCore.canonicalLanguage(id))
            aliases.forEach { assertEquals(id, HighlightCore.canonicalLanguage(it), it) }
        }
    }
}
