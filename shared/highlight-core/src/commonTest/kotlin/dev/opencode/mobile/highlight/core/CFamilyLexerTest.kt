package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Rust, Go, Java, C, C++, and C# beyond the golden fixtures: aliases, line terminators, odd
 * UTF-16, the line cap, unterminated constructs, sessions editing and streaming multi-line
 * strings, comments, and directives, and inputs built to be slow.
 */
class CFamilyLexerTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun idsAndAliasesResolve() {
        val expected =
            mapOf(
                "rust" to listOf("rust", "rs", "Rust"),
                "go" to listOf("go", "golang"),
                "java" to listOf("java"),
                "c" to listOf("c", "h"),
                "cpp" to listOf("cpp", "c++", "cc", "cxx", "hpp", "hh", "hxx", "C++"),
                "csharp" to listOf("csharp", "cs", "c#", "C#"),
            )
        for ((id, names) in expected) {
            assertTrue(id in HighlightCore.languages, id)
            for (name in names) assertEquals(id, HighlightCore.canonicalLanguage(name), name)
        }
    }

    @Test
    fun crlfTerminatorsStayOutsideSpans() {
        assertGolden("rust", "let s = \"a\r\nb\";\r\n/* c\r\n*/ x\r\n", "«Keyword|let» «Variable|s» «Operator|=» «String|\"a»␍\n«String|b\"»«Punctuation|;»␍\n«Comment|/* c»␍\n«Comment|*/» «Variable|x»␍\n")
        // A `\` before CRLF continues a directive and a string; the next directive starts fresh.
        assertGolden(
            "c",
            "#define A \\\r\n  # x\r\n#x\r\nchar *s = \"a\\\r\nb\";\r\n",
            "«Punctuation|#»«Keyword|define» «Constant|A» \\␍\n  «Operator|#» x␍\n«Punctuation|#»«Keyword|x»␍\n" +
                "«Keyword|char» «Operator|*»s «Operator|=» «String|\"a»«Constant|\\»␍\n«String|b\"»«Punctuation|;»␍\n",
        )
        assertGolden("go", "s := `a\r\nb`\r\n", "«Variable|s» «Operator|:=» «String|`a»␍\n«String|b`»␍\n")
        assertGolden("java", "s = \"\"\"\r\n  a\r\n  \"\"\";\r\n", "«Variable|s» «Operator|=» «String|\"\"\"»␍\n«String|  a»␍\n«String|  \"\"\"»«Punctuation|;»␍\n")
        assertGolden("csharp", "s = @\"a\r\nb\";\r\n", "«Variable|s» «Operator|=» «String|@\"a»␍\n«String|b\"»«Punctuation|;»␍\n")
        assertGolden("cpp", "s = R\"x(a\r\n)x\";\r\n", "s «Operator|=» «String|R\"x(a»␍\n«String|)x\"»«Punctuation|;»␍\n")
    }

    @Test
    fun emojiTabsAndLoneSurrogates() {
        assertGolden(
            "rust",
            "let c = '😀'; let d = '\uD83D'; // 😀\uDE00\n\"😀 \uD83D\" \uDE00x",
            "«Keyword|let» «Variable|c» «Operator|=» «String|'😀'»«Punctuation|;» «Keyword|let» «Variable|d» «Operator|=» «String|'⟨D83D⟩'»" +
                "«Punctuation|;» «Comment|// 😀⟨DE00⟩»\n«String|\"😀 ⟨D83D⟩\"» ⟨DE00⟩«Variable|x»",
        )
        assertGolden(
            "c",
            "char *s = \"😀\uD83D %d\";\tint \uDE00y; // é",
            "«Keyword|char» «Operator|*»s «Operator|=» «String|\"😀⟨D83D⟩ »«Constant|%d»«String|\"»«Punctuation|;»␉«Keyword|int» ⟨DE00⟩y«Punctuation|;» «Comment|// é»",
        )
        assertGolden("go", "\tx := '😀' + 'é'", "␉«Variable|x» «Operator|:=» «String|'😀'» «Operator|+» «String|'é'»")
        assertGolden(
            "csharp",
            "var s = $\"{😀} \uD83D\";",
            "«Keyword|var» «Variable|s» «Operator|=» «String|\$\"»«Punctuation|{»😀«Punctuation|}»«String| ⟨D83D⟩\"»«Punctuation|;»",
        )
    }

    @Test
    fun aLineCutAtTheCapKeepsTheStateItHadThere() {
        val limits = HighlightLimits(maxLineLength = 20)
        assertGolden(
            "cpp",
            "s = R\"d(abc" + "x".repeat(30) + "\nnext)d\"; int y",
            "s «Operator|=» «String|R\"d(abcxxxxxxxxx»" + "x".repeat(21) + "\n«String|next)d\"»«Punctuation|;» «Keyword|int» y",
            limits,
        )
        assertGolden(
            "rust",
            "s = r#\"abc" + "x".repeat(30) + "\nnext\"#; y",
            "«Variable|s» «Operator|=» «String|r#\"abcxxxxxxxxxx»" + "x".repeat(20) + "\n«String|next\"#»«Punctuation|;» «Variable|y»",
            limits,
        )
    }

    @Test
    fun unterminatedConstructsColorToTheEnd() {
        assertGolden("rust", "/* a /* b */\nc", "«Comment|/* a /* b */»\n«Comment|c»")
        assertGolden("rust", "r##\"open \"#\nx", "«String|r##\"open \"#»\n«String|x»")
        assertGolden("go", "`open\nx", "«String|`open»\n«String|x»")
        assertGolden("java", "\"\"\"\nopen \"\"", "«String|\"\"\"»\n«String|open \"\"»")
        assertGolden("csharp", "@\"open \"\"\nx", "«String|@\"open »«Constant|\"\"»\n«String|x»")
        assertGolden("csharp", "\$\"a {b\nc", "«String|\$\"a »«Punctuation|{»«Variable|b»\n«Variable|c»")
        assertGolden("csharp", "\$\"open\nx", "«String|\$\"open»\n«Variable|x»")
        assertGolden("csharp", "\"\"\"\nopen \"\"", "«String|\"\"\"»\n«String|open \"\"»")
        assertGolden("c", "s = \"a\\", "s «Operator|=» «String|\"a»«Constant|\\»")
        assertGolden("c", "#define X \\", "«Punctuation|#»«Keyword|define» «Constant|X» \\")
        assertGolden("c", "#include <stdio.h", "«Punctuation|#»«Keyword|include» «String|<stdio.h»")
        assertGolden("cpp", "R\"(open\n)x\"", "«String|R\"(open»\n«String|)x\"»")
        assertGolden("rust", "'", "'")
        assertGolden("csharp", "@", "@")
        assertGolden("cpp", "[[", "«Punctuation|[[»")
    }

    @Test
    fun seededCodeKeepsEveryProperty() {
        val random = Random(874)
        for (language in languages) {
            repeat(150) { HighlightPropertyChecks.checkAll(language, HighlightGenerators.code(language, random, random.nextInt(0, 2_000)), random) }
            repeat(30) {
                val text = HighlightGenerators.code(language, random, random.nextInt(0, 500))
                HighlightPropertyChecks.checkAll(language, text, random, HighlightLimits(maxLineLength = random.nextInt(1, 30)))
            }
        }
    }

    @Test
    fun streamingMultiLineConstructsOneCharacterAtATimeMatchesOnePass() {
        for ((language, text) in multiLineSources) {
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
    fun editsInsideMultiLineStringsCommentsAndDirectivesStopWhereStatesMatch() {
        val rust = session("rust", List(1_000) { "    let v$it = \"s$it\"; // $it" })
        // Opening a raw string changes every line below it, and so does closing it again.
        assertEquals(rust.lineCount, rust.insert(300, "r##\"").endLine)
        // `"#` is one hash short of closing it: only its own line changes.
        assertEquals(306, rust.insert(305, "\"# ").endLine)
        assertEquals(rust.lineCount, rust.insert(310, "\"##;").endLine)
        assertEquals(301, rust.insert(300, "/* /* */ */").endLine)
        assertMatchesOneShot(rust)

        val cpp = session("cpp", List(600) { "int v$it = f(\"s)\", $it);" })
        assertEquals(cpp.lineCount, cpp.insert(200, "auto s = R\"sql(").endLine)
        // A `)` followed by another delimiter does not close it.
        assertEquals(206, cpp.insert(205, ")sq\" )sqlx\" ").endLine)
        assertEquals(cpp.lineCount, cpp.insert(210, ")sql\";").endLine)
        assertMatchesOneShot(cpp)

        val c = session("c", List(600) { "int v$it = $it; /* $it */" })
        // A continued directive reaches one line further; the line after it ends it.
        assertEquals(102, c.insert(100, "#define X \\\n").endLine)
        // C comments do not nest: the next line's `*/` closes a lone `/*`, and states match after it.
        assertEquals(302, c.insert(300, "/*\n").endLine)
        assertMatchesOneShot(c)

        val cSharp = session("csharp", List(500) { "var v$it = F(a$it, 'c'); // $it" })
        assertEquals(cSharp.lineCount, cSharp.insert(100, "var s = @\"").endLine)
        assertEquals(106, cSharp.insert(105, "\"\" {x} ").endLine)
        assertEquals(cSharp.lineCount, cSharp.insert(110, "\";").endLine)
        assertMatchesOneShot(cSharp)

        val go = session("go", List(500) { "v$it := \"s$it\" // $it" })
        assertEquals(go.lineCount, go.insert(50, "raw := `").endLine)
        assertEquals(go.lineCount, go.insert(60, "`").endLine)
        assertMatchesOneShot(go)

        val java = session("java", List(500) { "String v$it = \"s$it\"; // $it" })
        assertEquals(java.lineCount, java.insert(50, "String t = \"\"\"").endLine)
        assertEquals(56, java.insert(55, "\"\" \\\" ").endLine)
        assertEquals(java.lineCount, java.insert(60, "\"\"\";").endLine)
        assertMatchesOneShot(java)
    }

    @Test
    fun slowShapedInputsStayLinear() {
        val limits = HighlightLimits(maxLineLength = 1_000_000)
        val inputs =
            listOf(
                "cpp" to "auto s = R\"0123456789abcdef(" + ")0123456789abcdeX\"".repeat(20_000),
                "cpp" to "R\"".repeat(100_000) + "x",
                "rust" to "r" + "#".repeat(200_000) + "\"" + "\"#".repeat(100_000),
                "rust" to "'a".repeat(100_000) + "b'x'".repeat(50_000),
                "rust" to "#[a::".repeat(100_000),
                "csharp" to "\$\$\$\"\"\"" + "{{".repeat(100_000) + "\"\"".repeat(100_000),
                "csharp" to "\$\"" + "{\$\"{".repeat(50_000),
                "csharp" to "[A.".repeat(100_000),
                "c" to "s = \"%" + "-".repeat(300_000) + "%[" + "1".repeat(100_000),
                "c" to "#".repeat(300_000) + "\n" + "\\".repeat(300_000) + "\n\"" + "\\".repeat(300_000),
                "c" to "1'" + "1'".repeat(150_000) + "0x1p" + "1.".repeat(100_000),
                "c" to "#include <" + "<".repeat(300_000),
                "go" to "`" + "%".repeat(300_000),
                "java" to "\"\"\"" + "\"\"\\\"".repeat(100_000),
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

    private fun session(
        language: String,
        lines: List<String>,
    ): HighlightSession = HighlightSession(language).also { it.open(lines.joinToString("\n"), Int.MAX_VALUE, never, noDeadline) }

    private fun HighlightSession.insert(
        line: Int,
        text: String,
    ): HighlightCallResult = replace(lineStart(line), lineStart(line), text, never, noDeadline).also { assertEquals(HighlightStatus.Complete, it.status) }

    private fun assertMatchesOneShot(session: HighlightSession) {
        val all = session.highlight(0, session.lineCount, never, noDeadline)
        assertEquals(describe(oneShot(session.language, session.text())), describe(all.spans.toIntArray()))
    }

    private val languages = listOf("rust", "go", "java", "c", "cpp", "csharp")

    private val multiLineSources =
        listOf(
            "rust" to "/* a /* b */\n c */ let s = \"x {y}\n z\";\nlet r = r##\"\n\"# \"##; let c = 'a'; fn f<'a>() {}\n",
            "go" to "s := `raw\n%d \"q\"\n` + \"%s\\n\"\n/* c\n*/ x := 'r'\n",
            "java" to "String s = \"\"\"\n  a \\\" \"\"\n  \"\"\"; /* c\n */ char c = '\\n';\n",
            "c" to "#define F(x) \\\n  do { g(\"%d\", x); } \\\n  # x\nint s = \"a\\\nb\"; /* c\n*/\n",
            "cpp" to "auto s = R\"x(\n)\" )y\"\n)x\"; [[nodiscard]] int f();\nstd::string t = u8\"a\";\n",
            "csharp" to "var s = \$@\"{a}\n\"\"{{\"; var r = \$\$\"\"\"\n{{x}} {\n\"\"\";\n#if DEBUG\n[Obsolete]\nclass A {}\n",
        )
}
