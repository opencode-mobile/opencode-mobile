package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Nim fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class NimGoldenTest {
    @Test
    fun proceduresTypesAndPragmas() {
        assertGolden(
            "nim",
            goldenLines(
                "## Sessions module.",
                "import std/[strutils, tables]",
                "type",
                "  Session* = object",
                "    name: string",
                "    count: int",
                "  Kind = enum kA, kB",
                "proc label*(count: int): string {.inline, raises: [].} =",
                "  result = \"sessions: \" & \$count & \"\\n\"",
                "func `\$`*(s: Session): string = s.name",
                "template check(x: untyped) = doAssert x",
                "iterator items(s: seq[int]): int = discard",
            ),
            goldenLines(
                "«Comment|## Sessions module.»",
                "«Keyword|import» std«Operator|/»«Punctuation|[»strutils«Punctuation|,» tables«Punctuation|]»",
                "«Keyword|type»",
                "  «Type|Session»«Operator|*» «Operator|=» «Keyword|object»",
                "    name«Operator|:» «Type|string»",
                "    count«Operator|:» «Type|int»",
                "  «Type|Kind» «Operator|=» «Keyword|enum» kA«Punctuation|,» kB",
                "«Keyword|proc» «Function|label»«Operator|*»«Punctuation|(»count«Operator|:» «Type|int»«Punctuation|)»«Operator|:» «Type|string» «Punctuation|{.»«Attribute|inline»«Punctuation|,» «Attribute|raises»«Operator|:» «Punctuation|[].}» «Operator|=»",
                "  «Variable|result» «Operator|=» «String|\"sessions: \"» «Operator|&» «Operator|\$»count «Operator|&» «String|\"»«Constant|\\n»«String|\"»",
                "«Keyword|func» «Punctuation|`»«Function|\$»«Punctuation|`»«Operator|*»«Punctuation|(»s«Operator|:» «Type|Session»«Punctuation|)»«Operator|:» «Type|string» «Operator|=» s«Punctuation|.»«Property|name»",
                "«Keyword|template» «Function|check»«Punctuation|(»x«Operator|:» «Type|untyped»«Punctuation|)» «Operator|=» «Function|doAssert» x",
                "«Keyword|iterator» «Function|items»«Punctuation|(»s«Operator|:» «Type|seq»«Punctuation|[»«Type|int»«Punctuation|])»«Operator|:» «Type|int» «Operator|=» «Keyword|discard»",
            ),
        )
    }

    @Test
    fun stringsCharactersAndNumbers() {
        assertGolden(
            "nim",
            goldenLines(
                "let s = \"\"\"triple",
                "\"quoted\" \\n no escape",
                "line\"\"\"\"",
                "let r = r\"C:\\path\"\"q\" & R\"x\" & sql\"select *\" & fmt\"\"\"{x}\"\"\"",
                "let c = 'a' & '\\n' & '\\x41' & '\\''",
                "let n = 0x1F'u8 + 1_000'i32 + 1.5e3 + 3u + 0b101 + 0o17'i64",
                "echo result.len, x.foo(), y.bar",
            ),
            goldenLines(
                "«Keyword|let» s «Operator|=» «String|\"\"\"triple»",
                "«String|\"quoted\" \\n no escape»",
                "«String|line\"\"\"\"»",
                "«Keyword|let» r «Operator|=» «String|r\"C:\\path\"\"q\"» «Operator|&» «String|R\"x\"» «Operator|&» «Function|sql»«String|\"select *\"» «Operator|&» «Function|fmt»«String|\"\"\"{x}\"\"\"»",
                "«Keyword|let» c «Operator|=» «String|'a'» «Operator|&» «String|'\\n'» «Operator|&» «String|'\\x41'» «Operator|&» «String|'\\''»",
                "«Keyword|let» n «Operator|=» «Number|0x1F'u8» «Operator|+» «Number|1_000'i32» «Operator|+» «Number|1.5e3» «Operator|+» «Number|3u» «Operator|+» «Number|0b101» «Operator|+» «Number|0o17'i64»",
                "«Function|echo» «Variable|result»«Punctuation|.»«Property|len»«Punctuation|,» x«Punctuation|.»«Function|foo»«Punctuation|(),» y«Punctuation|.»«Property|bar»",
            ),
        )
    }

    @Test
    fun commentsAndMultiLinePragmas() {
        assertGolden(
            "nim",
            goldenLines(
                "#[ block #[ nested ]# still ]# let x = 1",
                "##[ doc",
                "block ]## var y = 2",
                "{.push checks: off,",
                "  hints: off.}",
                "x = 1 #[ open",
                "still comment",
            ),
            goldenLines(
                "«Comment|#[ block #[ nested ]# still ]#» «Keyword|let» x «Operator|=» «Number|1»",
                "«Comment|##[ doc»",
                "«Comment|block ]##» «Keyword|var» y «Operator|=» «Number|2»",
                "«Punctuation|{.»«Attribute|push» checks«Operator|:» off«Punctuation|,»",
                "  hints«Operator|:» off«Punctuation|.}»",
                "x «Operator|=» «Number|1» «Comment|#[ open»",
                "«Comment|still comment»",
            ),
        )
    }

    @Test
    fun unterminatedConstructs() {
        assertGolden(
            "nim",
            goldenLines(
                "let s = \"open",
                "let t = \"\"\"never",
                "closed",
            ),
            goldenLines(
                "«Keyword|let» s «Operator|=» «String|\"open»",
                "«Keyword|let» t «Operator|=» «String|\"\"\"never»",
                "«String|closed»",
            ),
        )
    }

    @Test
    fun theLineCapCutsInsideAString() {
        assertGolden(
            "nim",
            goldenLines(
                "let s = \"abcdefgh\"",
                "echo s",
            ),
            goldenLines(
                "«Keyword|let» s «Operator|=» «String|\"a»bcdefgh\"",
                "«Function|echo» s",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "nim",
            "echo \"😀\"\r\n# \uD83D\r\nlet x = '\uDE00'\r\n",
            goldenLines(
                "«Function|echo» «String|\"😀\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "«Keyword|let» x «Operator|=» «String|'⟨DE00⟩'»␍",
                "",
            ),
        )
    }
}
