package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * COBOL, Fortran (both forms), Ada, Pascal, Visual Basic, ABAP, and SAS through the session:
 * shared properties on seeded input, streaming and edits across the constructs that span lines,
 * column rules under the line-length cap, and the worst cases. The timed randomized run covers
 * them through [HighlightGenerators.languages].
 */
class ClassicSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8724)
        for (language in ClassicGenerators.languages) {
            repeat(150) { round ->
                val text = HighlightGenerators.text(language, random, random.nextInt(0, 500))
                val limits = if (round % 7 == 0) HighlightLimits(maxLineLength = random.nextInt(1, 90)) else HighlightLimits.standard
                HighlightPropertyChecks.checkAll(language, text, random, limits)
            }
        }
    }

    @Test
    fun streamingOneCharacterAtATimeMatchesOnePass() {
        val samples =
            mapOf(
                "cobol" to "000100 IDENTIFICATION DIVISION.\n000200* note\n000300 PROCEDURE DIVISION.\n000400 MAIN.\n000500     DISPLAY \"a\n000600-    \"b\".\n>>SOURCE FREE\nX. MOVE 1 TO Y.\n",
                "fortran-fixed-form" to "C note\n      PRINT *, 'OPEN\nC between\n     1CLOSED', X\n   10 CONTINUE" + " ".repeat(60) + "TAIL\n",
                "fortran-free-form" to "program p\n  s = 'a &\n  &b' ! c\n  x = 1.0_dp .and. .true.\nend program p\n",
                "ada" to "procedure P is\nbegin\n   X := T'First + 'a'; -- c\nend P;\n",
                "pascal" to "begin { a\n b } (* c\n *) {\$I x\n} s := '''\n text\n ''';\nend.\n",
                "vb" to "Dim s = \"a\nb\" & \$\"x {If(a,\nb)} y\nz\"\n' c\n",
                "abap" to "* c\nDATA: a TYPE i,\n      b TYPE string.\nx = |t { cond #( when y = 1\n then 'z' ) } u|.\n",
                "sas" to "data a; /* c\n */ x = 'b\nc'd; * s\n c; datalines;\n1 2\n;\n%macro m; %mend;\n",
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
    fun editsInsideMultiLineConstructsStayLocal() {
        val cases =
            listOf(
                Triple("pascal", "x := 1; {\n" + List(500) { "  text $it 'q' (* c *)" }.joinToString("\n") + "\n}\ny := 2;", "  '''"),
                Triple("vb", "Dim s = \"\n" + List(500) { "text $it {x} 'q'" }.joinToString("\n") + "\n\"\nDim y = 1", "REM ' {"),
                Triple("sas", "data a;\n  datalines;\n" + List(500) { "$it 'q' /* x" }.joinToString("\n") + "\n;\nrun;", "* \"open"),
                Triple("fortran-fixed-form", "C top\n" + List(500) { "C     comment $it 'q'" }.joinToString("\n") + "\n      END", "C     'open"),
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
    fun closingAPascalCommentReLexesOnlyWhatChanged() {
        val session = HighlightSession("pascal")
        var spans = session.open(List(1_000) { "  x$it := 'a' + $it; (* c *)" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

        // An unclosed brace comment turns every line below it into comment.
        val at = session.lineEnd(400)
        val open = session.replace(at, at, " {", never, noDeadline)
        assertEquals(400, open.startLine)
        assertEquals(session.lineCount, open.endLine)
        spans = mergeEdit(session, spans, open, 2)
        assertContentEquals(oneShot("pascal", session.text()), spans)

        // Its close a few lines down turns everything below back into code.
        val close = session.lineEnd(403)
        val closed = session.replace(close, close, " }", never, noDeadline)
        assertEquals(403, closed.startLine)
        assertEquals(session.lineCount, closed.endLine)
        spans = mergeEdit(session, spans, closed, 2)
        assertContentEquals(oneShot("pascal", session.text()), spans)
    }

    @Test
    fun aLineFarBelowAnOpenStringIsStringAfterAStatePass() {
        val text = "Dim s = \"\n" + List(20_000) { "line $it" }.joinToString("\n") + "\n\"\nDim y = 1"
        val session = HighlightSession("vb")
        session.open(text, 0, never, noDeadline)
        val inside = session.lineCount - 3
        val result = session.highlight(inside, session.lineCount, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(inside)
        val shifted = result.spans.toIntArray().also { ints -> for (index in ints.indices step 3) ints[index] -= from }
        assertEquals(
            "«String|line 19999»\n«String|\"»\n«Keyword|Dim» y «Operator|=» «Number|1»",
            markup(text.substring(from), HighlightSpans(shifted, result.spans.count)),
        )
    }

    @Test
    fun columnRulesHoldUnderTheLineLengthCap() {
        // The cap cuts fixed-form lines before column 73, so nothing past it becomes a comment.
        val limits = HighlightLimits(maxLineLength = 40)
        val fortran = "      X = 1" + " ".repeat(70) + "SEQ"
        val cobol = "000100 MOVE 1 TO X." + " ".repeat(60) + "TAIL"
        for ((language, text) in listOf("fortran-fixed-form" to fortran, "cobol" to cobol)) {
            val result = HighlightCore.highlight(language, text, never, noDeadline, limits)
            assertEquals(HighlightStatus.Complete, result.status)
            assertSpansValid(text, result.spans, limits)
            assertTrue(result.spans.count > 0 && result.spans.end(result.spans.count - 1) <= 40, language)
        }
    }

    @Test
    fun aVeryLongLineKeepsItsStateForTheNextLine() {
        // The cap cuts the line inside a Pascal brace comment, which stays open below it.
        val text = "x := 1; { " + "a".repeat(30_000) + "\nstill } y := 2;"
        val result = HighlightCore.highlight("pascal", text, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        assertSpansValid(text, result.spans, HighlightLimits.standard)
        val second = text.indexOf('\n') + 1
        val index = (0 until result.spans.count).first { result.spans.start(it) >= second }
        assertEquals(HighlightRole.Comment, result.spans.role(index))
        assertEquals(second + "still }".length, result.spans.end(index))
    }

    @Test
    fun theWorstCasesFinishFast() {
        val inputs =
            mapOf(
                "cobol" to "000100 " + "PIC X(1) \"a\"\"\" *".repeat(20_000) + "\n" + "000200-    \"".repeat(30_000),
                "fortran-fixed-form" to "      X = '" + "''a".repeat(30_000) + "\n" + "     1'&\n".repeat(30_000),
                "fortran-free-form" to "s = '" + "a''&".repeat(40_000) + "\n" + "&'.and.&\n".repeat(30_000),
                "ada" to "X := " + "16#FF#'a'X'First".repeat(20_000),
                "pascal" to "s := '" + "''#13{(*".repeat(30_000) + "\n" + "'''\n".repeat(20_000),
                "vb" to "x = \$\"" + "{{{\$\"{".repeat(30_000) + "\n" + "{ \"a\n".repeat(20_000),
                "abap" to "x = |" + "{ |{ ".repeat(30_000) + "\n" + "DATA: a, ".repeat(30_000),
                "sas" to "x = \"" + "&&&a.\"\"".repeat(30_000) + "\n" + "* c;%* d;".repeat(30_000),
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
                "cobol" to listOf("COBOL"),
                "fortran-fixed-form" to listOf("f", "f77", "FOR"),
                "fortran-free-form" to listOf("f03", "f08", "f18", "f90", "f95", "Fortran"),
                "ada" to listOf("Ada"),
                "pascal" to listOf("delphi", "object-pascal"),
                "vb" to listOf("cmd", "visual-basic", "VB"),
                "abap" to listOf("ABAP"),
                "sas" to listOf("SAS"),
            )
        for ((id, aliases) in expected) {
            assertEquals(id, HighlightCore.canonicalLanguage(id))
            aliases.forEach { assertEquals(id, HighlightCore.canonicalLanguage(it), it) }
        }
    }
}
