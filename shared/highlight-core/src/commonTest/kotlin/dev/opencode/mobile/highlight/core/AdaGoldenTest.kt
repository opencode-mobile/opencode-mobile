package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Ada fixtures: the contract for its lexer. One escaped string per line keeps quotes,
 * `$`, CRLF, tabs, and lone surrogates exact.
 */
class AdaGoldenTest {
    @Test
    fun unitsDeclarationsAndCalls() {
        assertGolden(
            "ada",
            goldenLines(
                "with Ada.Text_IO; use Ada.Text_IO;",
                "procedure Sessions is",
                "   type Color is (Red, Green);",
                "   subtype Small is Integer range 1 .. 10;",
                "   Count : Natural := 0;",
                "begin",
                "   Put_Line (\"sessions: \" & Integer'Image (Count));",
                "   if Count /= 0 and then Ready then",
                "      null;",
                "   end if;",
                "end Sessions;",
                "package body Ada.Store is",
                "end Ada.Store;",
            ),
            goldenLines(
                "«Keyword|with» Ada«Punctuation|.»Text_IO«Punctuation|;» «Keyword|use» Ada«Punctuation|.»Text_IO«Punctuation|;»",
                "«Keyword|procedure» «Function|Sessions» «Keyword|is»",
                "   «Keyword|type» «Type|Color» «Keyword|is» «Punctuation|(»Red«Punctuation|,» Green«Punctuation|);»",
                "   «Keyword|subtype» «Type|Small» «Keyword|is» «Type|Integer» «Keyword|range» «Number|1» «Operator|..» «Number|10»«Punctuation|;»",
                "   Count «Punctuation|:» «Type|Natural» «Operator|:=» «Number|0»«Punctuation|;»",
                "«Keyword|begin»",
                "   «Function|Put_Line» «Punctuation|(»«String|\"sessions: \"» «Operator|&» «Type|Integer»«Punctuation|'»«Attribute|Image» «Punctuation|(»Count«Punctuation|));»",
                "   «Keyword|if» Count «Operator|/=» «Number|0» «Keyword|and» «Keyword|then» Ready «Keyword|then»",
                "      «Keyword|null»«Punctuation|;»",
                "   «Keyword|end» «Keyword|if»«Punctuation|;»",
                "«Keyword|end» «Function|Sessions»«Punctuation|;»",
                "«Keyword|package» «Keyword|body» «Function|Ada»«Punctuation|.»«Function|Store» «Keyword|is»",
                "«Keyword|end» «Function|Ada»«Punctuation|.»«Function|Store»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun characterLiteralsAndAttributes() {
        assertGolden(
            "ada",
            goldenLines(
                "C := 'a'; Q := '''; Blank := ' ';",
                "N := X'Length + Arr'First (1) + T'(others => 0);",
                "P := Obj.all'Access;",
                "if C in 'a' .. 'z' then Put ('x'); end if;",
            ),
            goldenLines(
                "C «Operator|:=» «String|'a'»«Punctuation|;» Q «Operator|:=» «String|'''»«Punctuation|;» Blank «Operator|:=» «String|' '»«Punctuation|;»",
                "N «Operator|:=» X«Punctuation|'»«Attribute|Length» «Operator|+» Arr«Punctuation|'»«Attribute|First» «Punctuation|(»«Number|1»«Punctuation|)» «Operator|+» T«Punctuation|'(»«Keyword|others» «Operator|=>» «Number|0»«Punctuation|);»",
                "P «Operator|:=» Obj«Punctuation|.»«Keyword|all»«Punctuation|'»«Attribute|Access»«Punctuation|;»",
                "«Keyword|if» C «Keyword|in» «String|'a'» «Operator|..» «String|'z'» «Keyword|then» «Function|Put» «Punctuation|(»«String|'x'»«Punctuation|);» «Keyword|end» «Keyword|if»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun numbersStringsAndOperators() {
        assertGolden(
            "ada",
            goldenLines(
                "X := 16#FF# + 2#1010_1010# + 16#F.F#E+2 + 1_000 + 3.14E-2;",
                "S := \"say \"\"hi\"\"\" & \"\";",
                "B := (A <= B) or (A >= B) xor not (A /= B);",
                "<<Label>> Y := X ** 2 mod 3 rem 4;",
            ),
            goldenLines(
                "X «Operator|:=» «Number|16#FF#» «Operator|+» «Number|2#1010_1010#» «Operator|+» «Number|16#F.F#E+2» «Operator|+» «Number|1_000» «Operator|+» «Number|3.14E-2»«Punctuation|;»",
                "S «Operator|:=» «String|\"say »«Constant|\"\"»«String|hi»«Constant|\"\"»«String|\"» «Operator|&» «String|\"\"»«Punctuation|;»",
                "B «Operator|:=» «Punctuation|(»A «Operator|<=» B«Punctuation|)» «Keyword|or» «Punctuation|(»A «Operator|>=» B«Punctuation|)» «Keyword|xor» «Keyword|not» «Punctuation|(»A «Operator|/=» B«Punctuation|);»",
                "«Operator|<<»Label«Operator|>>» Y «Operator|:=» X «Operator|**» «Number|2» «Keyword|mod» «Number|3» «Keyword|rem» «Number|4»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun unterminatedStringsAndComments() {
        assertGolden(
            "ada",
            goldenLines(
                "S := \"open",
                "T := 1; -- comment with \"quotes\"",
            ),
            goldenLines(
                "S «Operator|:=» «String|\"open»",
                "T «Operator|:=» «Number|1»«Punctuation|;» «Comment|-- comment with \"quotes\"»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "ada",
            "S := \"😀\";\r\nC := '😀'; -- é\r\nX := \uD83D;\r\n",
            goldenLines(
                "S «Operator|:=» «String|\"😀\"»«Punctuation|;»␍",
                "C «Operator|:=» «String|'😀'»«Punctuation|;» «Comment|-- é»␍",
                "X «Operator|:=» ⟨D83D⟩«Punctuation|;»␍",
                "",
            ),
        )
    }

    @Test
    fun aLineCutByTheCap() {
        assertGolden(
            "ada",
            goldenLines(
                "S := \"abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij\";",
                "X := 1;",
            ),
            goldenLines(
                "S «Operator|:=» «String|\"abcdefghijabcdefgh»ijabcdefghijabcdefghijabcdefghijabcdefghij\";",
                "X «Operator|:=» «Number|1»«Punctuation|;»",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }
}
