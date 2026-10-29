package dev.opencode.mobile.highlight.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Dart, Scala, Groovy, Objective-C, Zig, and Apex beyond the golden fixtures: aliases, line
 * terminators, odd UTF-16, the line cap, unterminated constructs, sessions editing and streaming
 * multi-line strings, comments, and queries, and inputs built to be slow.
 */
class CLikeExtraLexerTest {
    private val never = HighlightCancellation.Never
    private val noDeadline = HighlightDeadline.never

    @Test
    fun idsAndAliasesResolve() {
        val expected =
            mapOf(
                "dart" to listOf("dart", "Dart"),
                "scala" to listOf("scala"),
                "groovy" to listOf("groovy"),
                "objective-c" to listOf("objective-c", "objc", "objectivec", "ObjC"),
                "zig" to listOf("zig"),
                "apex" to listOf("apex"),
            )
        for ((id, names) in expected) {
            assertTrue(id in HighlightCore.languages, id)
            for (name in names) assertEquals(id, HighlightCore.canonicalLanguage(name), name)
        }
    }

    @Test
    fun crlfTerminatorsStayOutsideSpans() {
        assertGolden(
            "dart",
            "var s = '''a\r\n\$b\r\n''';\r\n/* c\r\n*/\r\n",
            "«Keyword|var» s «Operator|=» «String|'''a»␍\n«String|\$»«Variable|b»␍\n«String|'''»«Punctuation|;»␍\n«Comment|/* c»␍\n«Comment|*/»␍\n",
        )
        assertGolden("scala", "val s = \"\"\"a\r\n\"\"\"\r\n", "«Keyword|val» s «Operator|=» «String|\"\"\"a»␍\n«String|\"\"\"»␍\n")
        assertGolden("groovy", "x = \$/a\r\n/\$\r\n", "x «Operator|=» «String|\$/a»␍\n«String|/\$»␍\n")
        assertGolden("zig", "const s =\r\n    \\\\a\r\n;\r\n", "«Keyword|const» «Variable|s» «Operator|=»␍\n    «String|\\\\a»␍\n«Punctuation|;»␍\n")
        assertGolden(
            "apex",
            "x = [SELECT Id\r\nFROM A];\r\n",
            "«Variable|x» «Operator|=» «Punctuation|[»«Keyword|SELECT» «Variable|Id»␍\n«Keyword|FROM» «Variable|A»«Punctuation|];»␍\n",
        )
        assertGolden("objective-c", "s = @\"a\\\r\nb\";\r\n", "s «Operator|=» «String|@\"a»«Constant|\\»␍\n«String|b\"»«Punctuation|;»␍\n")
    }

    @Test
    fun emojiTabsAndLoneSurrogates() {
        assertGolden(
            "dart",
            "var s = '😀 \$é \uD83D';\t// 😀\uDE00",
            "«Keyword|var» s «Operator|=» «String|'😀 \$»«Variable|é»«String| ⟨D83D⟩'»«Punctuation|;»␉«Comment|// 😀⟨DE00⟩»",
        )
        assertGolden("scala", "val c = '😀'; s\"\uDE00\$x\"", "«Keyword|val» c «Operator|=» «String|'😀'»«Punctuation|;» «Keyword|s»«String|\"⟨DE00⟩»«Punctuation|\$»«Variable|x»«String|\"»")
        assertGolden("groovy", "\"😀 \$a.b \uD83D\"", "«String|\"😀 »«Variable|\$a.b»«String| ⟨D83D⟩\"»")
        assertGolden("zig", "const c = '😀'; \\\\\uD83D", "«Keyword|const» «Variable|c» «Operator|=» «String|'😀'»«Punctuation|;» «String|\\\\⟨D83D⟩»")
        assertGolden("objective-c", "@\"😀 %@\uDE00\"", "«String|@\"😀 »«Constant|%@»«String|⟨DE00⟩\"»")
        assertGolden("apex", "String s = '😀\uD83D';", "«Keyword|String» «Variable|s» «Operator|=» «String|'😀⟨D83D⟩'»«Punctuation|;»")
    }

    @Test
    fun aLineCutAtTheCapKeepsTheStateItHadThere() {
        val limits = HighlightLimits(maxLineLength = 20)
        assertGolden(
            "dart",
            "s = r'''abc" + "x".repeat(30) + "\nnext'''; y",
            "s «Operator|=» «String|r'''abcxxxxxxxxx»" + "x".repeat(21) + "\n«String|next'''»«Punctuation|;» y",
            limits,
        )
        assertGolden(
            "apex",
            "x = [SELECT " + "a".repeat(30) + "\nFROM b];",
            "«Variable|x» «Operator|=» «Punctuation|[»«Keyword|SELECT» «Variable|aaaaaaaa»" + "a".repeat(22) + "\n«Keyword|FROM» «Variable|b»«Punctuation|];»",
            limits,
        )
    }

    @Test
    fun veryLongLinesColorUpToTheStandardCap() {
        val long = "y".repeat(50_000)

        // The y's a line keeps after a prefix of [length] characters, and the ones cut off past the cap.
        fun kept(length: Int) = "y".repeat(20_000 - length)

        fun cut(length: Int) = "y".repeat(30_000 + length)
        assertGolden("dart", "x = '''$long\n''';", "x «Operator|=» «String|'''${kept(7)}»${cut(7)}\n«String|'''»«Punctuation|;»")
        assertGolden("scala", "s\"\"\"$long\n\"\"\"", "«Keyword|s»«String|\"\"\"${kept(4)}»${cut(4)}\n«String|\"\"\"»")
        assertGolden("groovy", "\$/$long\n/\$", "«String|\$/${kept(2)}»${cut(2)}\n«String|/\$»")
        assertGolden("objective-c", "// $long\nx", "«Comment|// ${kept(3)}»${cut(3)}\nx")
        assertGolden("zig", "\\\\$long\nx", "«String|\\\\${kept(2)}»${cut(2)}\n«Variable|x»")
        assertGolden("apex", "[select $long\nfrom]", "«Punctuation|[»«Keyword|select» «Variable|${kept(8)}»${cut(8)}\n«Keyword|from»«Punctuation|]»")
    }

    @Test
    fun unterminatedConstructsColorToTheEnd() {
        assertGolden("dart", "'open \$x\ny", "«String|'open \$»«Variable|x»\ny")
        assertGolden("dart", "'''open\ny", "«String|'''open»\n«String|y»")
        assertGolden("dart", "r'open\ny", "«String|r'open»\ny")
        assertGolden("dart", "'a \${b\nc", "«String|'a »«Punctuation|\${»b\nc")
        assertGolden("scala", "s\"open \${a\nb", "«Keyword|s»«String|\"open »«Punctuation|\${»a\nb")
        assertGolden("scala", "\"\"\"open\nx", "«String|\"\"\"open»\n«String|x»")
        assertGolden("scala", "/* a /* b */\nc", "«Comment|/* a /* b */»\n«Comment|c»")
        assertGolden("groovy", "x = /open\ny", "x «Operator|=» «Operator|/»open\ny")
        assertGolden("groovy", "\$/open\nx", "«String|\$/open»\n«String|x»")
        assertGolden("groovy", "'''open\nx", "«String|'''open»\n«String|x»")
        assertGolden("objective-c", "@\"open\nx", "«String|@\"open»\nx")
        assertGolden("objective-c", "@", "@")
        assertGolden("zig", "@\"open", "«Variable|@\"open»")
        assertGolden("zig", "/* not a comment", "«Operator|/*» «Variable|not» «Variable|a» «Variable|comment»")
        assertGolden("apex", "[SELECT Id\nFROM", "«Punctuation|[»«Keyword|SELECT» «Variable|Id»\n«Keyword|FROM»")
        assertGolden("apex", "'open\nx", "«String|'open»\n«Variable|x»")
    }

    @Test
    fun seededCodeKeepsEveryProperty() {
        val random = Random(875)
        for (language in CLikeExtraGenerators.languages) {
            repeat(150) { HighlightPropertyChecks.checkAll(language, HighlightGenerators.code(language, random, random.nextInt(0, 2_000)), random) }
            repeat(30) {
                val text = HighlightGenerators.code(language, random, random.nextInt(0, 500))
                HighlightPropertyChecks.checkAll(language, text, random, HighlightLimits(maxLineLength = random.nextInt(1, 30)))
            }
        }
    }

    @Test
    fun goldenSourcesKeepEveryProperty() {
        val random = Random(876)
        for ((language, source) in multiLineSources) {
            repeat(20) { HighlightPropertyChecks.checkAll(language, source, random) }
            HighlightPropertyChecks.checkAll(language, source, random, HighlightLimits(maxLineLength = 7))
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
    fun editsInsideMultiLineStringsCommentsAndQueriesStopWhereStatesMatch() {
        val dart = session("dart", List(1_000) { "  var v$it = 's$it'; // $it" })
        // Opening a triple-quoted string changes every line below it, and so does closing it again.
        assertEquals(dart.lineCount, dart.insert(300, "var t = '''").endLine)
        // Text and interpolations inside it change nothing below their own line.
        assertEquals(306, dart.insert(305, "text \$x \${y} ").endLine)
        assertEquals(dart.lineCount, dart.insert(310, "''';").endLine)
        assertEquals(401, dart.insert(400, "/* /* */ */").endLine)
        assertMatchesOneShot(dart)

        val scala = session("scala", List(600) { "val v$it = s\"a\$x$it\" // $it" })
        assertEquals(scala.lineCount, scala.insert(100, "val t = s\"\"\"").endLine)
        assertEquals(106, scala.insert(105, "\"\" \$\$ \${z} ").endLine)
        assertEquals(scala.lineCount, scala.insert(110, "\"\"\"").endLine)
        assertMatchesOneShot(scala)

        val groovy = session("groovy", List(600) { "def v$it = \"g\$x$it\" // $it" })
        assertEquals(groovy.lineCount, groovy.insert(200, "def d = \$/").endLine)
        assertEquals(206, groovy.insert(205, "\$\$ \$/ / ").endLine)
        assertEquals(groovy.lineCount, groovy.insert(210, "/\$").endLine)
        assertEquals(groovy.lineCount, groovy.insert(300, "def t = \"\"\"").endLine)
        assertEquals(groovy.lineCount, groovy.insert(310, "\"\"\"").endLine)
        assertMatchesOneShot(groovy)

        val apex = session("apex", List(600) { "Integer v$it = list[$it]; // $it" })
        assertEquals(apex.lineCount, apex.insert(100, "x = [SELECT Id ").endLine)
        // A `[` inside the query nests; only its own line changes once it closes there.
        assertEquals(106, apex.insert(105, "a[0] ").endLine)
        assertEquals(apex.lineCount, apex.insert(110, "] ").endLine)
        assertMatchesOneShot(apex)

        val objc = session("objective-c", List(500) { "NSString *v$it = @\"s$it\"; /* $it */" })
        assertEquals(51, objc.insert(50, "#define X \\\n").endLine - 1)
        assertEquals(102, objc.insert(100, "/*\n").endLine)
        assertMatchesOneShot(objc)

        val zig = session("zig", List(500) { "const v$it = \"s$it\"; // $it" })
        // Zig line strings and comments never reach the next line.
        assertEquals(51, zig.insert(50, "\\\\ \"open /* ").endLine)
        assertMatchesOneShot(zig)
    }

    @Test
    fun slowShapedInputsStayLinear() {
        val limits = HighlightLimits(maxLineLength = 1_000_000)
        val inputs =
            listOf(
                "dart" to "'" + "\${'\${".repeat(50_000),
                "dart" to "r'''" + "''".repeat(100_000),
                "scala" to "s\"\"\"" + "\$\$\"\"".repeat(100_000),
                "scala" to "'a".repeat(100_000),
                "groovy" to "x = " + "/".repeat(300_000),
                "groovy" to "( /" + "\\".repeat(300_000),
                "groovy" to "\"" + "\$a.".repeat(100_000),
                "groovy" to "\$/" + "\$\$/".repeat(100_000),
                "objective-c" to "[a " + "b:[c d:".repeat(50_000),
                "objective-c" to "- (" + "x ".repeat(100_000),
                "objective-c" to "@\"" + "%".repeat(300_000),
                "zig" to "@\"" + "\\".repeat(300_000),
                "apex" to "[" + " ".repeat(300_000) + "SELECT",
                "apex" to "[SELECT " + "[".repeat(100_000) + "]".repeat(100_000),
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

    private val multiLineSources =
        listOf(
            "dart" to "var a = '''\n\$x \${'\${y}'}\n''' + r\"\"\"\n\$raw\n\"\"\";\n/* a /* b */\n c */ var b = \"\${\n  1 }\";\n",
            "scala" to "val a = s\"\"\"\n \$x \${y}\n\"\"\"\" + \"\"\"\nraw \\n\n\"\"\"\n/* a /* b */\n c */ val c = 'x'\n",
            "groovy" to "def a = \"\"\"\n \$x.y \${z}\n\"\"\" + '''\n\\n\n'''\ndef d = \$/\n\$\$ \$/ x\n/\$\ndef r = /a\\/b/\n",
            "objective-c" to "#define F(x) \\\n  [x foo:@\"%@\"]\n@interface A : NSObject\n- (void)a:(int)b c:(id)d;\n@end\n/* c\n*/\n",
            "zig" to "const s =\n    \\\\one \"q\"\n    \\\\two\n;\nconst c = '\\n'; // x\nconst b = @import(\"std\");\n",
            "apex" to "List<Account> a = [\n  SELECT Id FROM Account\n  WHERE Name = :n\n];\n/* c\n*/ String s = 'x';\n",
        )
}
