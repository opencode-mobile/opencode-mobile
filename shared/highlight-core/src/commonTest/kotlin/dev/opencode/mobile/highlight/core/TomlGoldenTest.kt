package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written TOML fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class TomlGoldenTest {
    @Test
    fun tablesKeysAndValues() {
        assertGolden(
            "toml",
            goldenLines(
                "# comment",
                "[project]",
                "name = \"opencode\"",
                "enabled = true",
                "limit = 3",
                "\"quoted key\".bare-key = 'lit'",
                "site.\"google.com\" = true",
                "[[bin]]",
                "path = 'src/main.rs'",
                "[ tool . poetry ]",
            ),
            goldenLines(
                "«Comment|# comment»",
                "«Punctuation|[»«Type|project»«Punctuation|]»",
                "«Property|name» «Punctuation|=» «String|\"opencode\"»",
                "«Property|enabled» «Punctuation|=» «Constant|true»",
                "«Property|limit» «Punctuation|=» «Number|3»",
                "«Property|\"quoted key\"»«Punctuation|.»«Property|bare-key» «Punctuation|=» «String|'lit'»",
                "«Property|site»«Punctuation|.»«Property|\"google.com\"» «Punctuation|=» «Constant|true»",
                "«Punctuation|[[»«Type|bin»«Punctuation|]]»",
                "«Property|path» «Punctuation|=» «String|'src/main.rs'»",
                "«Punctuation|[» «Type|tool» «Punctuation|.» «Type|poetry» «Punctuation|]»",
            ),
        )
    }

    @Test
    fun numbersDatesAndTimes() {
        assertGolden(
            "toml",
            goldenLines(
                "ints = [1, +2, -3, 2_000, 0xDEAD_beef, 0o17, 0b1010]",
                "floats = [1e6, -3.14, 6.626e-34, inf, -inf, nan]",
                "odt = 1979-05-27T07:32:00Z",
                "odt2 = 1979-05-27 07:32:00.999-07:00",
                "ld = 1979-05-27",
                "lt = 07:32:00",
            ),
            goldenLines(
                "«Property|ints» «Punctuation|=» «Punctuation|[»«Number|1»«Punctuation|,» «Number|+2»«Punctuation|,» «Number|-3»«Punctuation|,» «Number|2_000»«Punctuation|,» «Number|0xDEAD_beef»«Punctuation|,» «Number|0o17»«Punctuation|,» «Number|0b1010»«Punctuation|]»",
                "«Property|floats» «Punctuation|=» «Punctuation|[»«Number|1e6»«Punctuation|,» «Number|-3.14»«Punctuation|,» «Number|6.626e-34»«Punctuation|,» «Number|inf»«Punctuation|,» «Number|-inf»«Punctuation|,» «Number|nan»«Punctuation|]»",
                "«Property|odt» «Punctuation|=» «Number|1979-05-27T07:32:00Z»",
                "«Property|odt2» «Punctuation|=» «Number|1979-05-27 07:32:00.999-07:00»",
                "«Property|ld» «Punctuation|=» «Number|1979-05-27»",
                "«Property|lt» «Punctuation|=» «Number|07:32:00»",
            ),
        )
    }

    @Test
    fun stringsAndEscapes() {
        assertGolden(
            "toml",
            goldenLines(
                "basic = \"tab\\t quote\\\" unicode\\u00e9 \\U0001F600\"",
                "lit = 'C:\\Users\\path'",
                "ml = \"\"\"",
                "line one \\",
                "  two \\t\"\"\"",
                "mll = '''",
                "raw \\n ''quoted'' '''",
                "five = \"\"\"\"\"quotes\"\"\"\"\"",
            ),
            goldenLines(
                "«Property|basic» «Punctuation|=» «String|\"tab»«Constant|\\t»«String| quote»«Constant|\\\"»«String| unicode»«Constant|\\u00e9»«String| »«Constant|\\U0001F600»«String|\"»",
                "«Property|lit» «Punctuation|=» «String|'C:\\Users\\path'»",
                "«Property|ml» «Punctuation|=» «String|\"\"\"»",
                "«String|line one »«Constant|\\»",
                "«String|  two »«Constant|\\t»«String|\"\"\"»",
                "«Property|mll» «Punctuation|=» «String|'''»",
                "«String|raw \\n ''quoted'' '''»",
                "«Property|five» «Punctuation|=» «String|\"\"\"\"\"quotes\"\"\"\"\"»",
            ),
        )
    }

    @Test
    fun inlineTablesAndArraysAcrossLines() {
        assertGolden(
            "toml",
            goldenLines(
                "deps = { serde = { version = \"1\", features = [\"derive\"] }, x.y = 1 }",
                "array = [",
                "  \"a\", # comment",
                "  [1, 2],",
                "  { k = \"v\" },",
                "]",
                "[next]",
            ),
            goldenLines(
                "«Property|deps» «Punctuation|=» «Punctuation|{» «Property|serde» «Punctuation|=» «Punctuation|{» «Property|version» «Punctuation|=» «String|\"1\"»«Punctuation|,» «Property|features» «Punctuation|=» «Punctuation|[»«String|\"derive\"»«Punctuation|]» «Punctuation|},» «Property|x»«Punctuation|.»«Property|y» «Punctuation|=» «Number|1» «Punctuation|}»",
                "«Property|array» «Punctuation|=» «Punctuation|[»",
                "  «String|\"a\"»«Punctuation|,» «Comment|# comment»",
                "  «Punctuation|[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|],»",
                "  «Punctuation|{» «Property|k» «Punctuation|=» «String|\"v\"» «Punctuation|},»",
                "«Punctuation|]»",
                "«Punctuation|[»«Type|next»«Punctuation|]»",
            ),
        )
    }

    @Test
    fun unterminatedStrings() {
        assertGolden(
            "toml",
            goldenLines(
                "a = \"open",
                "b = 1",
                "c = \"\"\"",
                "still string",
            ),
            goldenLines(
                "«Property|a» «Punctuation|=» «String|\"open»",
                "«Property|b» «Punctuation|=» «Number|1»",
                "«Property|c» «Punctuation|=» «String|\"\"\"»",
                "«String|still string»",
            ),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "toml",
            "a = \"😀\"\r\n[t]\r\nb = '\uD83D'\r\n",
            goldenLines(
                "«Property|a» «Punctuation|=» «String|\"😀\"»␍",
                "«Punctuation|[»«Type|t»«Punctuation|]»␍",
                "«Property|b» «Punctuation|=» «String|'⟨D83D⟩'»␍",
                "",
            ),
        )
    }
}
