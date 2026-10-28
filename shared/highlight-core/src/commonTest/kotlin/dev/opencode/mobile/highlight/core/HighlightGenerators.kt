package dev.opencode.mobile.highlight.core

import kotlin.random.Random

/** Random inputs for property tests and the randomized run: plausible code per language, damaged freely. */
internal object HighlightGenerators {
    val languages = listOf("json", "diff", "typescript", "javascript", "kotlin", "swift", "python", "rust", "go", "java", "c", "cpp", "csharp", "plaintext") + ScriptingGenerators.languages + GlueGenerators.languages + WebGenerators.languages + SpecialtyGenerators.languages +
            LiteralStackGenerators.languages + ClassicGenerators.languages +
            CLikeExtraGenerators.languages

    private val jsonPieces =
        listOf(
            "{", "}", "[", "]", ":", ",", " ", " ", "\n", "\n", "\r\n", "\"key\"", "\"str\\\"esc\\u00e9\"", "'s'", "\"open",
            "123", "-1.5e3", "0x1F", ".5", "true", "null", "NaN", "bare", "// note", "/* c */", "/* open", "*/", "\\",
            "😀", "\uD83D", "\uDE00", "​", "\r", "\t", "@",
        )

    private val cLikePieces =
        listOf(
            "{", "}", "(", ")", "[", "]", ";", ",", ".", " ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\"s\\\"q\"", "\"open", "'c'",
            "'open", "123", "0x1F", "1_000", ".5e-3", "1.5f", "foo", "Bar", "MAX_X", "if", "return", "class", "true", "null", "// line",
            "/* c */", "/* open", "*/", "/*", "\\", "=", "=>", "->", "<", ">", "/", "?.", "::", ":", "@Ann", "#", "${'$'}", "${'$'}{", "😀",
            "\uD83D", "\uDE00", "​", "\"", "'",
        )

    private val cPieces =
        listOf(
            "\n#include <stdio.h>", "\n#define X(a) \\", "\n#if A", "\n#endif", "\n#", "##", "\\", "\\\n", "\"cont \\\n", "L\"w\"",
            "u8'x'", "1'000", "0x1.8p3", "%d", "\"%-8.3lf %%\"", "NULL", "size_t", "struct", "typedef", "'\\''",
        )

    /** Pieces per language on top of [cLikePieces]; a language listed here gets code from its pieces. */
    private val codePieces: Map<String, List<String>> =
        mapOf(
            "typescript" to
                cLikePieces +
                listOf(
                    "`", "`tpl ${'$'}{", "/re[/]x/g", "<div>", "</div>", "<A b=\"c\" d={", "/>", "<>", "</>", "<T,>", "#priv", "this.x",
                    "async", "type", "interface", "function", "#!", "x: string", "= <", "return <p>",
                ),
            "javascript" to
                cLikePieces +
                listOf("`", "`t${'$'}{", "${'$'}{", "/a\\/b/i", "<b>", "</b>", "<i x={", "/>", "<>", "</>", "#p", "async", "function", "#!"),
            "kotlin" to
                cLikePieces +
                listOf(
                    "\"\"\"", "\"${'$'}name ${'$'}{", "\"\"\"raw ${'$'}x ${'$'}{", "`name`", "'\\n'", "/* /* nested */", "fun", "val", "data",
                    "@file:Ann", "${'$'}x",
                ),
            "swift" to
                cLikePieces +
                listOf(
                    "\"\"\"", "\"\\(", "\\(", "#\"raw\"#", "##\"", "\"##", "#\"\"\"", "\"\"\"#", "\\#(", "#if", "#endif",
                    "/* /* nested */", "func", "let", "nil", "${'$'}0", "`default`",
                ),
            "rust" to
                cLikePieces +
                listOf(
                    "r#\"raw\"#", "r##\"", "\"##", "\"#", "br\"", "c\"s\"", "b'x'", "'a", "'\\n'", "'😀'", "#[derive(", "#![", "]",
                    "println!(", "vec![", "/* /* nested */", "fn", "let", "impl", "Self", "self", "1u32", "2.5f64", "\"{x} {{y}}\"", "{}",
                    "&'static", "r#type", ".await", "x @ 1..=9", "\\\n",
                ),
            "go" to
                cLikePieces +
                listOf(
                    "`", "`raw", "\"%5.2f %[1]d %%\"", "'r'", "'\\''", "func", "package", "type", "iota", "nil", "chan", "<-", ":=",
                    "0x1p-2", "2i", "struct{", "map[string]int",
                ),
            "java" to
                cLikePieces +
                listOf(
                    "\"\"\"", "\"\"\"\ntext \\\"\"\"\n\"\"\"", "@Override", "'\\u0041'", "100L", "0x1.8p3", "record", "class",
                    "List<String>", "var", "@interface",
                ),
            "c" to cLikePieces + cPieces,
            "cpp" to
                cLikePieces + cPieces +
                listOf(
                    "R\"(", ")\"", "R\"d(", ")d\"", "LR\"x(", ")x\"", "u8R\"", "[[nodiscard]]", "[[", "]]", "template<typename T>",
                    "std::", "nullptr", "1'000'000",
                ),
            "csharp" to
                cLikePieces +
                listOf(
                    "@\"", "${'$'}\"", "${'$'}@\"", "@${'$'}\"", "\"\"\"", "\"\"\"\"", "${'$'}${'$'}\"\"\"", "{{", "}}", "{", "}", "\"\"",
                    "\n[Serializable]", "\n[Obsolete(", "\n#region x", "\n#if DEBUG", "\n#endif", "@class", "1.5m", "get;", "${'$'}",
                ),
            "python" to
                listOf(
                    "(", ")", "[", "]", "{", "}", ":", ",", ".", " ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\\", "#", "# note",
                    "'s'", "\"s\\\"q\"", "'open", "\"open", "'''", "\"\"\"", "r'\\d'", "b\"x\"", "f'", "f\"", "f\"{x!r:>{w}}\"", "rf'''",
                    "{{", "}}", "{", "}", "!r", ":>4", "123", "0x1F", "1_000", ".5e-3", "2j", "def", "class", "match", "case", "_",
                    "lambda", "True", "None", "self", "foo", "Bar", "MAX_X", "@dec", "->", "=", "==", "and", "😀", "\uD83D", "\uDE00", "​",
                ),
        ) + ScriptingGenerators.pieces + GlueGenerators.pieces + WebGenerators.pieces + SpecialtyGenerators.pieces + LiteralStackGenerators.pieces + ClassicGenerators.pieces + CLikeExtraGenerators.pieces

    private val diffLines =
        listOf(
            "diff --git a/x b/x", "index 1a2b..3c4d 100644", "--- a/x", "+++ b/x", "@@ -1,3 +1,4 @@ fun f()", "@@ -1 +1 @@",
            "@@ -0,0 +1,2 @@", "@@", "@@@ -1 -1 +1 @@@", "+added", "-removed", " context", "", "\\ No newline at end of file",
            "--- removed comment", "+++ added", "new file mode 100644", "rename from a", "> right", "< left", "plain words",
            "@@ -1,99999 +1,2 @@", "+😀\uD83D",
        )

    fun text(
        language: String,
        random: Random,
        size: Int,
    ): String =
        when (random.nextInt(10)) {
            0 -> soup(random, size)
            else ->
                when (language) {
                    "diff" -> diff(random, size)
                    in codePieces -> pieces(codePieces.getValue(language), random, size)
                    else -> json(random, size)
                }
        }

    fun code(
        language: String,
        random: Random,
        size: Int,
    ): String = pieces(codePieces.getValue(language), random, size)

    private fun pieces(
        pieces: List<String>,
        random: Random,
        size: Int,
    ): String = buildString { while (length < size) append(pieces[random.nextInt(pieces.size)]) }

    fun json(
        random: Random,
        size: Int,
    ): String = buildString { while (length < size) append(jsonPieces[random.nextInt(jsonPieces.size)]) }

    fun diff(
        random: Random,
        size: Int,
    ): String =
        buildString {
            while (length < size) {
                append(diffLines[random.nextInt(diffLines.size)])
                append(if (random.nextInt(8) == 0) "\r\n" else "\n")
            }
        }

    /** Any UTF-16 units: binary, lone surrogates, control characters, line breaks. */
    fun soup(
        random: Random,
        size: Int,
    ): String =
        CharArray(size) {
            when (random.nextInt(12)) {
                0 -> '\n'
                1 -> '\r'
                2 -> random.nextInt(0xD800, 0xE000).toChar()
                3 -> "{}[]:,\"'\\/*-+@ ".random(random)
                else -> random.nextInt(0, 0x10000).toChar()
            }
        }.concatToString()

    /** Splits [text] into 1 to 8 random chunks, sometimes splitting a CRLF or a surrogate pair. */
    fun chunks(
        text: String,
        random: Random,
    ): List<String> {
        if (text.isEmpty()) return listOf("")
        val cuts = List(random.nextInt(0, 8)) { random.nextInt(0, text.length + 1) }.sorted()
        val result = mutableListOf<String>()
        var at = 0
        for (cut in cuts + text.length) {
            result += text.substring(at, cut)
            at = cut
        }
        return result
    }
}
