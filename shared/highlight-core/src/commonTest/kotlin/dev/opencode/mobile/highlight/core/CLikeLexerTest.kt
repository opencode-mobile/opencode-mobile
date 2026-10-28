package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The C-like lexer beyond the golden fixtures: line terminators, odd UTF-16, the line cap,
 * unterminated constructs, sessions editing multi-line strings and comments, and inputs built to
 * be slow.
 */
class CLikeLexerTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun crlfTerminatorsStayOutsideSpans() {
        assertGolden(
            "typescript",
            "let a = \"x\";\r\n/* c\r\n*/ let b = `t\r\n\${a}`;\r\n",
            "«Keyword|let» «Variable|a» «Operator|=» «String|\"x\"»«Punctuation|;»␍\n«Comment|/* c»␍\n" +
                "«Comment|*/» «Keyword|let» «Variable|b» «Operator|=» «String|`t»␍\n«Punctuation|\${»«Variable|a»«Punctuation|}»«String|`»«Punctuation|;»␍\n",
        )
        assertGolden(
            "swift",
            "let s = \"\"\"\r\n  a \\(b)\r\n  \"\"\"\r\n",
            "«Keyword|let» s «Operator|=» «String|\"\"\"»␍\n«String|  a »«Punctuation|\\(»b«Punctuation|)»␍\n«String|  \"\"\"»␍\n",
        )
    }

    @Test
    fun emojiSurrogatesAndZeroWidthCharacters() {
        assertGolden(
            "typescript",
            "const café = \"😀 \uD83D x\u200B\"; \uDE00 // 😀\uD83D\nlet `e` = 1",
            "«Keyword|const» «Variable|café» «Operator|=» «String|\"😀 ⟨D83D⟩ x\u200B\"»«Punctuation|;» ⟨DE00⟩ «Comment|// 😀⟨D83D⟩»\n" +
                "«Keyword|let» «String|`e`» «Operator|=» «Number|1»",
        )
        assertGolden(
            "kotlin",
            "val s = \"😀\$x\uD83D\" + '\uDE00' // é\u0000\n`😀`()",
            "«Keyword|val» s «Operator|=» «String|\"😀»«Variable|\$x»«String|⟨D83D⟩\"» «Operator|+» «String|'⟨DE00⟩'» «Comment|// é\u0000»\n" +
                "«Function|`😀`»«Punctuation|()»",
        )
    }

    @Test
    fun aLineCutAtTheCapKeepsTheStateItHadThere() {
        assertGolden(
            "typescript",
            "const s = `abc" + "x".repeat(30) + "\nnext`; let y",
            "«Keyword|const» «Variable|s» «Operator|=» «String|`abcxxxxxx»" + "x".repeat(24) + "\n«String|next`»«Punctuation|;» «Keyword|let» «Variable|y»",
            HighlightLimits(maxLineLength = 20),
        )
    }

    @Test
    fun unterminatedConstructsColorToTheEnd() {
        assertGolden("typescript", "/* open\nstill", "«Comment|/* open»\n«Comment|still»")
        assertGolden("typescript", "`a \${b +\n`c", "«String|`a »«Punctuation|\${»«Variable|b» «Operator|+»\n«String|`c»")
        assertGolden("javascript", "x = 'open\ny", "«Variable|x» «Operator|=» «String|'open»\n«Variable|y»")
        assertGolden("kotlin", "\"\"\"open \$x\nmore", "«String|\"\"\"open »«Variable|\$x»\n«String|more»")
        assertGolden("kotlin", "\"open \${a\nb", "«String|\"open »«Punctuation|\${»a\nb")
        assertGolden("swift", "#\"open\nlet x", "«String|#\"open»\n«Keyword|let» x")
        assertGolden("swift", "##\"\"\"\nopen \"\"\"#\n\"\"\"##", "«String|##\"\"\"»\n«String|open \"\"\"#»\n«String|\"\"\"##»")
        assertGolden("tsx", "<div a=\"1\"\n  b>text", "«Punctuation|<»«Tag|div» «Attribute|a»«Operator|=»«String|\"1\"»\n  «Attribute|b»«Punctuation|>»text")
    }

    @Test
    fun goldenSourcesKeepEveryProperty() {
        val random = Random(872)
        for ((language, source) in sessionSources) {
            repeat(20) { HighlightPropertyChecks.checkAll(language, source, random) }
            HighlightPropertyChecks.checkAll(language, source, random, HighlightLimits(maxLineLength = 7))
        }
    }

    @Test
    fun seededCodeKeepsEveryProperty() {
        val random = Random(873)
        for (language in listOf("typescript", "javascript", "kotlin", "swift")) {
            repeat(100) { HighlightPropertyChecks.checkAll(language, HighlightGenerators.code(language, random, random.nextInt(0, 2_000)), random) }
        }
    }

    @Test
    fun editsInsideMultiLineStringsAndCommentsStopWhereStatesMatch() {
        val kotlin = HighlightSession("kotlin")
        kotlin.open(List(1_000) { "    val v$it = \"s$it\" // $it" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline)
        // Opening a raw string changes every line below it, and so does closing it again.
        assertEquals(kotlin.lineCount, kotlin.insert(300, "\"\"\"").endLine)
        assertEquals(kotlin.lineCount, kotlin.insert(310, "\"\"\".x").endLine)
        // Text and templates inside it change nothing below their own line.
        assertEquals(306, kotlin.insert(305, "text ").endLine)
        assertEquals(307, kotlin.insert(306, "\$x \${y} ").endLine)
        assertEquals(kotlin.lineCount, kotlin.knownStateLines)
        assertMatchesOneShot(kotlin)

        // `/* /*` opens two levels; the line that closes both is where states match again.
        val swift = HighlightSession("swift")
        swift.open(List(500) { "let a$it = 1 /* c */" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline)
        assertEquals(121, swift.insert(120, "*/ */ ").endLine)
        assertEquals(121, swift.insert(100, "/* /* ").endLine)
        assertEquals(106, swift.insert(105, "x ").endLine)
        assertEquals(121, swift.insert(110, "*/ ").endLine)
        assertMatchesOneShot(swift)

        // Templates nested across lines: the closing line converges.
        val typeScript = HighlightSession("typescript")
        typeScript.open(List(500) { "const a$it = `x\${y}`;" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline)
        assertEquals(206, typeScript.insert(205, "}`}`;").endLine)
        assertEquals(206, typeScript.insert(200, "const t = `\${`\${").endLine)
        assertEquals(203, typeScript.insert(202, "{ `\${{}}` } ").endLine)
        assertMatchesOneShot(typeScript)

        // A JSX element whose tags span lines.
        val tsx = HighlightSession("tsx")
        tsx.open(List(300) { "const e$it = <a b={$it}>{c}</a>;" }.joinToString("\n"), Int.MAX_VALUE, never, noDeadline)
        assertEquals(51, tsx.insert(50, "</p>; ").endLine)
        assertEquals(51, tsx.insert(40, "x = <p\n  q=\"r\">").endLine - 1)
        assertMatchesOneShot(tsx)
    }

    private fun HighlightSession.insert(
        line: Int,
        text: String,
    ): HighlightCallResult = replace(lineStart(line), lineStart(line), text, never, noDeadline).also { assertEquals(HighlightStatus.Complete, it.status) }

    private fun assertMatchesOneShot(session: HighlightSession) {
        val all = session.highlight(0, session.lineCount, never, noDeadline)
        assertEquals(describe(oneShot(session.language, session.text())), describe(all.spans.toIntArray()))
    }

    @Test
    fun slowShapedInputsStayLinear() {
        val limits = HighlightLimits(maxLineLength = 1_000_000)
        val inputs =
            listOf(
                "javascript" to buildString { while (length < 300_000) append("function a${'$'}length(b,c){return b+c*\"x\"}var d=[1,2,3],e={f:1,g:'h',i:/[a-z]+/g};") },
                "typescript" to "const x = " + "(".repeat(100_000) + "a",
                "typescript" to "let x: " + "A<".repeat(100_000) + "B" + ">".repeat(100_000) + ";",
                "typescript" to "const f = " + "(a, b) => ".repeat(30_000) + "a;",
                "typescript" to "x = /[" + "/[".repeat(100_000),
                "tsx" to "x = " + "<a b={".repeat(50_000),
                "typescript" to "const t = `\n" + "line \${a} `b` \${\n".repeat(30_000),
                "kotlin" to "\"" + "\${\"\${".repeat(50_000),
                "swift" to "\"" + "\\(\"\\(".repeat(50_000) + "\n" + "#".repeat(100_000) + "\"",
                "typescript" to "async ".repeat(50_000) + " ".repeat(100_000) + "x",
            )
        for ((language, text) in inputs) {
            val started = TimeSource.Monotonic.markNow()
            val result = HighlightCore.highlight(language, text, never, noDeadline, limits)
            val elapsed = started.elapsedNow().inWholeMilliseconds
            assertEquals(HighlightStatus.Complete, result.status)
            assertSpansValid(text, result.spans, limits)
            // Quadratic work on these inputs would take minutes; linear work takes milliseconds.
            assertTrue(elapsed < 10_000, "$language input of ${text.length} chars took $elapsed ms")
        }
    }

    private val sessionSources =
        listOf(
            "typescript" to "const multi = `one\ntwo \${\n  items.map((x) => `<\${x}>`).join(\",\")\n} three\nfour`;\n/* across\n lines */ let b = /re/g;\n",
            "tsx" to "return (\n  <ul a=\"1\"\n    b={x}>\n    {items.map((i) => <li key={i}>{i}</li>)}\n    <>t</>\n  </ul>\n);\nconst id = <T,>(x: T) => x;\n",
            "kotlin" to "/* a /* b */\n c */ val raw = \"\"\"\n  \$id \${x.y}\n\"\"\"\nval s = \"a \${b} \$c\"\n",
            "swift" to "/* a /* b */\n c */ let s = \"\"\"\n  \\(x) \\(\"y\")\n  \"\"\"\nlet r = ##\"\n\"##\nlet m = #\"\"\"\n\\#(x)\n\"\"\"#\n",
        )
}
