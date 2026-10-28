package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Haskell, OCaml, F#, Clojure, Common Lisp, Prolog, and Smalltalk through the session: shared
 * properties on seeded input, streaming and edits across nested comments, quasi-quotes, quoted
 * strings, interpolation holes, and literal arrays, deep nesting, and the worst cases. The timed
 * randomized run covers them through [HighlightGenerators.languages].
 */
class SymbolicSessionTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun propertiesHoldForSeededInputs() {
        val random = Random(8727)
        for (language in SymbolicGenerators.languages) {
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
                "haskell" to "{-# LANGUAGE X\n #-}\nf = \"a\\\n  \\b\" {- x {- y\n-} z -}\ng = [q|one\ntwo|] -- c\nh x' = 'a'\n",
                "ocaml" to "let s = {id|a\n|x} b|id} (* x (* y\n*) z *)\nlet t = \"m\nn\" and c = 'q'\n",
                "fsharp" to "let s = \$\"a {x +\n  1} b\"\nlet t = \$\$\"\"\"{{y}}\nz\"\"\" (* a (* b\n*) c *)\n[<A>]\nlet! u = @\"v\n\"\"w\"\n",
                "clojure" to "(defn f [x]\n  \"doc\nmore\" #_(a\n b) #\"r\ne\" \\a)\n",
                "common-lisp" to "(defun f (x) \"s\nt\" #| a #| b\n|# c |# #\\a)\n",
                "prolog" to "a(X) :- X = 'q\\\nr', /* c\nd */ b.\n",
                "smalltalk" to "x := #(a #(b\n c) 'd\ne') \"f\ng\" foo: \$h.\n",
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
    fun openingAndClosingANestedCommentReLexesOnlyWhatChanged() {
        val openers = mapOf("haskell" to ("{- {-" to "-} -}"), "ocaml" to ("(* (*" to "*) *)"), "fsharp" to ("(* (*" to "*) *)"), "common-lisp" to ("#| #|" to "|# |#"))
        for ((language, delimiters) in openers) {
            val (open, close) = delimiters
            val session = HighlightSession(language)
            val lines = List(1_000) { if (language == "common-lisp") "(f \"line $it\" 'x)" else "x$it = \"line $it\" ++ y" }
            var spans = session.open(lines.joinToString("\n"), Int.MAX_VALUE, never, noDeadline).spans.toIntArray()

            // A comment opened two deep turns every line below it into comment.
            val at = session.lineEnd(400)
            val opened = session.replace(at, at, " $open", never, noDeadline)
            assertEquals(400, opened.startLine, language)
            assertEquals(session.lineCount, opened.endLine, language)
            spans = mergeEdit(session, spans, opened, open.length + 1)
            assertContentEquals(oneShot(language, session.text()), spans, language)

            // One close leaves it one deep, so everything below stays comment: the state differs by depth.
            val first = session.lineEnd(402)
            val inner = close.substring(0, 2)
            val half = session.replace(first, first, "\n$inner", never, noDeadline)
            assertEquals(402, half.startLine, language)
            assertEquals(session.lineCount, half.endLine, language)
            spans = mergeEdit(session, spans, half, inner.length + 1)
            assertContentEquals(oneShot(language, session.text()), spans, language)

            // The second close turns everything below back into code.
            val second = session.lineEnd(405)
            val closed = session.replace(second, second, "\n$inner", never, noDeadline)
            assertEquals(405, closed.startLine, language)
            assertEquals(session.lineCount, closed.endLine, language)
            spans = mergeEdit(session, spans, closed, inner.length + 1)
            assertContentEquals(oneShot(language, session.text()), spans, language)

            // Typing inside the comment stops at the edited line.
            val inside = session.lineStart(404) + 2
            val typed = session.replace(inside, inside, "\" '", never, noDeadline)
            assertEquals(404, typed.startLine, language)
            assertEquals(405, typed.endLine, language)
            spans = mergeEdit(session, spans, typed, 3)
            assertContentEquals(oneShot(language, session.text()), spans, language)
        }
    }

    @Test
    fun editsInsideMultiLineConstructsStayLocal() {
        val cases =
            listOf(
                Triple("haskell", "q = [sql|\n" + List(500) { "SELECT $it -- \"q\" {-" }.joinToString("\n") + "\n|]\ny = 1", "\"open"),
                Triple("ocaml", "let q = {x|\n" + List(500) { "line $it \"q\" (* c" }.joinToString("\n") + "\n|x}\nlet y = 1", "{y|"),
                Triple("fsharp", "let q = \"\"\"\n" + List(500) { "line $it \"q\" (* c {x}" }.joinToString("\n") + "\n\"\"\"\nlet y = 1", "(* open"),
                Triple("clojure", "(str \"\n" + List(500) { "line $it (f) ; c" }.joinToString("\n") + "\n\")\n(g)", "#_("),
                Triple("smalltalk", "x := #(\n" + List(500) { "a$it ( b )" }.joinToString("\n") + "\n). y := 1", "'q' \$a"),
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
    fun deepCommentNestingCountsEveryLevel() {
        val delimiters = mapOf("haskell" to ("{-" to "-}"), "ocaml" to ("(*" to "*)"), "fsharp" to ("(*" to "*)"), "common-lisp" to ("#|" to "|#"))
        for ((language, pair) in delimiters) {
            val (open, close) = pair
            // 3,000 levels over 30 lines, closed one short, then the last close.
            val text = List(30) { open.repeat(100) }.joinToString("\n") + "\n" + List(30) { close.repeat(100) }.joinToString("\n").dropLast(2) + "\n$close x 1"
            val result = HighlightCore.highlight(language, text, never, noDeadline)
            assertEquals(HighlightStatus.Complete, result.status, language)
            val lastLine = markup(text, result.spans).substringAfterLast('\n')
            assertEquals("«Comment|$close» x «Number|1»", lastLine, language)

            // Past the saturation depth, openers stop counting but the state stays bounded and stable.
            val deep = List(400) { open.repeat(100) }.joinToString("\n") + "\nx"
            val session = HighlightSession(language)
            val first = session.open(deep, Int.MAX_VALUE, never, noDeadline)
            assertEquals(HighlightStatus.Complete, first.status)
            val edit = session.replace(deep.length - 1, deep.length, "y", never, noDeadline)
            assertEquals(session.lineCount - 1, edit.startLine, language)
        }
    }

    @Test
    fun aVeryLongLineColorsUpToTheCap() {
        val openers = mapOf("haskell" to "x = \"", "ocaml" to "let x = \"", "fsharp" to "let x = \"", "clojure" to "(str \"", "common-lisp" to "(f \"", "prolog" to "a('", "smalltalk" to "x := '")
        for ((language, opener) in openers) {
            val source = opener + "a".repeat(100_000) + "\nnext"
            val result = HighlightCore.highlight(language, source, never, noDeadline)
            assertEquals(HighlightStatus.Complete, result.status, language)
            assertSpansValid(source, result.spans, HighlightLimits.standard)
            val capped = (0 until result.spans.count).first { result.spans.role(it) == HighlightRole.String }
            assertEquals(HighlightLimits.standard.maxLineLength, result.spans.end(capped), language)
        }
    }

    @Test
    fun theWorstCasesFinishFast() {
        val inputs =
            mapOf(
                "haskell" to "x = " + "{- [q| \"\\\n".repeat(20_000) + "\n" + "{-# ".repeat(20_000) + "\n" + "'\\".repeat(50_000),
                "ocaml" to "let x = " + "(* {a| \"\n".repeat(20_000) + "\n" + "{abcdefgh|".repeat(10_000) + "|abcdefg}".repeat(10_000),
                "fsharp" to "let x = " + "\$\"{\$\$\"\"\"{{ (* \n".repeat(20_000) + "\n" + "[<A(".repeat(20_000) + "\n" + "``a".repeat(30_000),
                "clojure" to "(" + "#_ #_(\"\\".repeat(20_000) + "\n" + "#_(".repeat(20_000) + "\n" + "\\".repeat(60_000),
                "common-lisp" to "(" + "#| \"\\ #\\".repeat(20_000) + "\n" + "(defun f (".repeat(20_000),
                "prolog" to "a :- " + "'\\\n".repeat(20_000) + "\n" + "/* 0'".repeat(20_000),
                "smalltalk" to "x := " + "#( '\"".repeat(20_000) + "\n" + "#(".repeat(30_000) + ")".repeat(30_000),
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
                "haskell" to listOf("hs", "Haskell"),
                "ocaml" to listOf("OCaml"),
                "fsharp" to listOf("f#", "fs", "F#"),
                "clojure" to listOf("clj"),
                "common-lisp" to listOf("lisp", "Common-Lisp"),
                "prolog" to listOf("Prolog"),
                "smalltalk" to listOf(" smalltalk "),
            )
        for ((id, aliases) in expected) {
            assertTrue(id in HighlightCore.languages, id)
            assertEquals(id, HighlightCore.canonicalLanguage(id))
            aliases.forEach { assertEquals(id, HighlightCore.canonicalLanguage(it), it) }
        }
    }

}
