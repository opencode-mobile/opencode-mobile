package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Python through the session: shared properties on random input, streaming, and edits across triple-quoted strings. */
class PythonSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(872)
        repeat(400) { round ->
            val text = PythonInputs.text(random, random.nextInt(0, 500))
            val limits = if (round % 7 == 0) HighlightLimits(maxLineLength = random.nextInt(1, 30)) else HighlightLimits.standard
            HighlightPropertyChecks.checkAll("python", text, random, limits)
        }
    }

    /**
     * The randomized run for Python, on the same clock as [HighlightRandomizedTest]: a second by
     * default, `HIGHLIGHT_FUZZ_SECONDS` to lengthen it, `HIGHLIGHT_FUZZ_SEED` to replay.
     */
    @Test
    fun randomInputsKeepEveryProperty() {
        val seconds = testEnvironment("HIGHLIGHT_FUZZ_SECONDS")?.toDoubleOrNull() ?: 1.0
        val seed = testEnvironment("HIGHLIGHT_FUZZ_SEED")?.toLongOrNull() ?: Random.nextLong()
        val random = Random(seed)
        val until = TimeSource.Monotonic.markNow() + seconds.seconds
        var rounds = 0
        while (!until.hasPassedNow()) {
            val roundSeed = random.nextLong()
            val roundRandom = Random(roundSeed)
            val size = if (roundRandom.nextInt(50) == 0) roundRandom.nextInt(5_000, 60_000) else roundRandom.nextInt(0, 800)
            val text = PythonInputs.text(roundRandom, size)
            val limits = if (roundRandom.nextInt(5) == 0) HighlightLimits(maxLineLength = roundRandom.nextInt(1, 64)) else HighlightLimits.standard
            try {
                HighlightPropertyChecks.checkAll("python", text, roundRandom, limits)
            } catch (failure: Throwable) {
                fail("Round $rounds (run seed $seed, round seed $roundSeed, python, ${text.length} chars) failed: ${failure.message}", failure)
            }
            rounds += 1
        }
        println("PythonSessionTest: $rounds rounds in $seconds s, seed $seed")
    }

    @Test
    fun streamingATripleQuotedFStringOneCharacterAtATimeMatchesOnePass() {
        val text = "def f(x):\n    s = f'''head {x!r:>{w}}\n  {[\n 1, \"two\" ]} {{lit}}\n'''  # done\n    return \"\"\"doc\n\"\"\"\n"
        val expected = oneShot("python", text)
        val session = HighlightSession("python")
        var spans = session.open("", Int.MAX_VALUE, never, noDeadline).spans.toIntArray()
        for (char in text) {
            val before = session.lineCount
            val result = session.append(char.toString(), never, noDeadline)
            assertEquals(HighlightStatus.Complete, result.status)
            assertTrue(result.startLine >= before - 1)
            spans = mergeEdit(session, spans, result, 1)
        }
        assertContentEquals(expected, spans, "streamed ${describe(spans)}\none-shot ${describe(expected)}")
    }

    @Test
    fun openingAndClosingATripleQuoteReLexesOnlyWhatChanged() {
        val lines = List(1_000) { "x$it = f(\"s$it\")  # c" }
        val session = HighlightSession("python")
        val original = lines.joinToString("\n")
        var spans = session.open(original, Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

        // An unclosed triple quote turns every line below it into string.
        val at = session.lineStart(400)
        val open = session.replace(at, at, "'''", never, noDeadline)
        assertEquals(400, open.startLine)
        assertEquals(session.lineCount, open.endLine)
        spans = mergeEdit(session, spans, open, 3)
        assertContentEquals(oneShot("python", session.text()), spans)

        // Closing it a few lines down turns every line below back into code.
        val close = session.lineEnd(403)
        val closed = session.replace(close, close, "'''", never, noDeadline)
        assertEquals(403, closed.startLine)
        assertEquals(session.lineCount, closed.endLine)
        spans = mergeEdit(session, spans, closed, 3)
        assertContentEquals(oneShot("python", session.text()), spans)

        // Typing inside the triple-quoted text stops at the edited line.
        val inside = session.lineStart(402) + 2
        val typed = session.replace(inside, inside, "'' \"", never, noDeadline)
        assertEquals(402, typed.startLine)
        assertEquals(403, typed.endLine)
        spans = mergeEdit(session, spans, typed, 4)
        assertContentEquals(oneShot("python", session.text()), spans)

        // A closed multi-line string converges on the edit's last line.
        val above = session.lineStart(100)
        val insert = "y = $Q3" + "a\nb$Q3 "
        val block = session.replace(above, above, insert, never, noDeadline)
        assertEquals(100, block.startLine)
        assertEquals(102, block.endLine)
        spans = mergeEdit(session, spans, block, insert.length)
        assertContentEquals(oneShot("python", session.text()), spans)
    }

    @Test
    fun aLineFarBelowAnOpenTripleQuoteIsStringAfterAStatePass() {
        val text = "x = $Q3 start\n" + List(20_000) { "line $it = 'v'" }.joinToString("\n") + "\n$Q3\ny = 1"
        val session = HighlightSession("python")
        session.open(text, 0, never, noDeadline)
        val inside = session.lineCount - 3
        val result = session.highlight(inside, session.lineCount, never, noDeadline)
        assertEquals(HighlightStatus.Complete, result.status)
        val from = session.lineStart(inside)
        val shiftedSpans = result.spans.toIntArray().also { ints -> for (index in ints.indices step 3) ints[index] -= from }
        assertEquals(
            "«String|line 19999 = 'v'»\n«String|$Q3»\ny «Operator|=» «Number|1»",
            markup(text.substring(from), HighlightSpans(shiftedSpans, result.spans.count)),
        )
    }

    @Test
    fun theUnclosedStringWorstCaseFinishesFast() {
        val text = "s = \"" + "abc \\\" ".repeat(14_300)
        val mark = TimeSource.Monotonic.markNow()
        val result = HighlightCore.highlight("python", text, never, HighlightDeadline.after(500.0))
        assertEquals(HighlightStatus.Complete, result.status)
        assertTrue(mark.elapsedNow() < 2.seconds, "took ${mark.elapsedNow()}")
    }

    @Test
    fun aliasesResolveToPython() {
        listOf("python", "py", "PY3", " python3 ", "pyi").forEach { assertEquals("python", HighlightCore.canonicalLanguage(it)) }
    }

    private companion object {
        const val Q3 = "\"\"\""
    }
}

/** Random Python-shaped input: plausible tokens with every string form, damaged freely. */
internal object PythonInputs {
    private val pieces =
        listOf(
            " ", " ", "    ", "\n", "\n", "\n", "\r\n", "\t", "x", "self", ".", ".attr", "(", ")", "[", "]", "{", "}", ":", ",", ";",
            "=", "->", ":=", "!", "!r", "@", "@decorator", "def ", "class ", "lambda", "match ", "case ", "type ", "_", "if", "in",
            "None", "print", "MAX", "Name", "0x1F", "1_000.5e-3j", ".5", "# comment", "#", "\\", "\\\n",
            "'", "\"", "'''", "\"\"\"", "'s'", "\"d\"", "r'\\d'", "b\"\\x00\"", "f'", "f\"", "f'''", "f\"\"\"", "rf'", "Rb\"", "t'",
            "f'{x}'", "f\"{x!r:>{w}.2f}\"", "{{", "}}", "\\n", "\\N{EM DASH}", "\\u00e9", "\\'", "\\\"",
            "😀", "\uD83D", "\uDE00", "\u200B", "\u000C", "é",
        )

    fun text(
        random: Random,
        size: Int,
    ): String =
        if (random.nextInt(10) == 0) {
            HighlightGenerators.soup(random, size)
        } else {
            buildString { while (length < size) append(pieces[random.nextInt(pieces.size)]) }
        }
}
