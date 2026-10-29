package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written JSON fixtures: the contract for the JSON lexer. */
class JsonGoldenTest {
    @Test
    fun objectsArraysNumbersAndLiterals() {
        assertGolden(
            "json",
            """
            {
              "id": "s-1",
              "n": -12.5e3,
              "ok": true,
              "none": null,
              "list": [1, 0x1F, false],
              "empty": {}
            }
            """.trimIndent(),
            """
            «Punctuation|{»
              «Property|"id"»«Punctuation|:» «String|"s-1"»«Punctuation|,»
              «Property|"n"»«Punctuation|:» «Number|-12.5e3»«Punctuation|,»
              «Property|"ok"»«Punctuation|:» «Constant|true»«Punctuation|,»
              «Property|"none"»«Punctuation|:» «Constant|null»«Punctuation|,»
              «Property|"list"»«Punctuation|:» «Punctuation|[»«Number|1»«Punctuation|,» «Number|0x1F»«Punctuation|,» «Constant|false»«Punctuation|],»
              «Property|"empty"»«Punctuation|:» «Punctuation|{}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun escapesInsideStrings() {
        // Regular strings: the backslashes below are JSON escapes, written out as `\\`.
        assertGolden(
            "json",
            "[\"a\\\"b\\u00e9\\n\\uZZ\", \"tab\\tx\", \"\\\\\"]",
            "«Punctuation|[»«String|\"a»«Constant|\\\"»«String|b»«Constant|\\u00e9\\n\\u»«String|ZZ\"»«Punctuation|,» " +
                "«String|\"tab»«Constant|\\t»«String|x\"»«Punctuation|,» «String|\"»«Constant|\\\\»«String|\"»«Punctuation|]»",
        )
    }

    @Test
    fun keysIncludingJson5Forms() {
        assertGolden(
            "json5",
            """{"a\"b" : 1, 'single': 'v', bare: NaN, "x":-Infinity, "y": .5, "z": +1}""",
            """«Punctuation|{»«Property|"a\"b"» «Punctuation|:» «Number|1»«Punctuation|,» «Property|'single'»«Punctuation|:» «String|'v'»""" +
                """«Punctuation|,» «Property|bare»«Punctuation|:» «Number|NaN»«Punctuation|,» «Property|"x"»«Punctuation|:»-«Number|Infinity»""" +
                """«Punctuation|,» «Property|"y"»«Punctuation|:» «Number|.5»«Punctuation|,» «Property|"z"»«Punctuation|:» «Number|+1»«Punctuation|}»""",
        )
    }

    @Test
    fun lineAndBlockComments() {
        assertGolden(
            "jsonc",
            """
            // leading comment
            {
              /* inline */ "a": 1, // trailing
              /* open
                 still comment "k": 1
              */ "b": [true]
            }
            """.trimIndent(),
            """
            «Comment|// leading comment»
            «Punctuation|{»
              «Comment|/* inline */» «Property|"a"»«Punctuation|:» «Number|1»«Punctuation|,» «Comment|// trailing»
              «Comment|/* open»
            «Comment|     still comment "k": 1»
            «Comment|  */» «Property|"b"»«Punctuation|:» «Punctuation|[»«Constant|true»«Punctuation|]»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun unterminatedStringsEndAtTheirLine() {
        assertGolden(
            "json",
            """
            {"open": "abc
              "next": 1}
            ["x\
            """.trimIndent(),
            """
            «Punctuation|{»«Property|"open"»«Punctuation|:» «String|"abc»
              «Property|"next"»«Punctuation|:» «Number|1»«Punctuation|}»
            «Punctuation|[»«String|"x»«Constant|\»
            """.trimIndent(),
        )
    }

    @Test
    fun crlfLinesLexLikeLfLines() {
        assertGolden(
            "json",
            "{\r\n  \"a\": [1,\r\n2]\r\n}\r\n",
            "«Punctuation|{»␍\n  «Property|\"a\"»«Punctuation|:» «Punctuation|[»«Number|1»«Punctuation|,»␍\n«Number|2»«Punctuation|]»␍\n«Punctuation|}»␍\n",
        )
    }

    @Test
    fun emojiZeroWidthAndLoneSurrogates() {
        assertGolden(
            "json",
            "{\"😀\": \"é\uD83D x​\", \"\uDE00k\": 1}\n\uD83D @ 😀",
            "«Punctuation|{»«Property|\"😀\"»«Punctuation|:» «String|\"é⟨D83D⟩ x​\"»«Punctuation|,» «Property|\"⟨DE00⟩k\"»" +
                "«Punctuation|:» «Number|1»«Punctuation|}»\n⟨D83D⟩ @ 😀",
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "json",
            "[1, 2, 3, 4, 5]\n\"abcdefghijkl\": 1\n[6]",
            "«Punctuation|[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|,» «Number|3»«Punctuation|,» 4, 5]\n" +
                "«String|\"abcdefghi»jkl\": 1\n«Punctuation|[»«Number|6»«Punctuation|]»",
            HighlightLimits(maxLineLength = 10),
        )
    }

    @Test
    fun strayCharactersStayPlain() {
        assertGolden(
            "json",
            "{@ #}\u0000\u0001 - . +x",
            "«Punctuation|{»@ #«Punctuation|}»\u0000\u0001 - . +x",
        )
    }
}
