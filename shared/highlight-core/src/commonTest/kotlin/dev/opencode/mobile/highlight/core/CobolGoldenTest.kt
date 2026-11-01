package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written COBOL fixtures, free and fixed reference format: the contract for its lexer. One
 * escaped string per line keeps quotes, `$`, CRLF, tabs, and lone surrogates exact.
 */
class CobolGoldenTest {
    @Test
    fun freeFormatDivisionsParagraphsAndVerbs() {
        assertGolden(
            "cobol",
            goldenLines(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SESSIONS.",
                "PROCEDURE DIVISION.",
                "MAIN-PARA.",
                "    display \"sessions: 3\" *> inline",
                "    Perform Calc-Para Thru Calc-Exit",
                "    IF WS-N >= 10 AND NOT DONE GO TO MAIN-PARA END-IF",
                "    MOVE FUNCTION UPPER-CASE(WS-NAME) TO WS-NAME",
                "    STOP RUN.",
                "CALC-PARA SECTION.",
            ),
            goldenLines(
                "«Keyword|IDENTIFICATION» «Keyword|DIVISION»«Punctuation|.»",
                "«Keyword|PROGRAM-ID»«Punctuation|.» «Function|SESSIONS»«Punctuation|.»",
                "«Keyword|PROCEDURE» «Keyword|DIVISION»«Punctuation|.»",
                "«Function|MAIN-PARA»«Punctuation|.»",
                "    «Keyword|display» «String|\"sessions: 3\"» «Comment|*> inline»",
                "    «Keyword|Perform» «Function|Calc-Para» «Keyword|Thru» «Function|Calc-Exit»",
                "    «Keyword|IF» WS-N «Operator|>=» «Number|10» «Keyword|AND» «Keyword|NOT» DONE «Keyword|GO» «Keyword|TO» «Function|MAIN-PARA» «Keyword|END-IF»",
                "    «Keyword|MOVE» «Keyword|FUNCTION» «Function|UPPER-CASE»«Punctuation|(»WS-NAME«Punctuation|)» «Keyword|TO» WS-NAME",
                "    «Keyword|STOP» «Keyword|RUN»«Punctuation|.»",
                "«Function|CALC-PARA» «Keyword|SECTION»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun dataDivisionLevelsPicturesAndFigurativeConstants() {
        assertGolden(
            "cobol",
            goldenLines(
                "WORKING-STORAGE SECTION.",
                "01  WS-REC.",
                "    05  WS-AMOUNT    PIC S9(5)V99 COMP-3 VALUE ZERO.",
                "    05  WS-NAME      PICTURE IS X(10) VALUE SPACES.",
                "    05  WS-EDIT      PIC ZZ,ZZ9.99-.",
                "77  WS-QUOTE        PIC X(5) VALUE \"it\"\"s\".",
                "88  IS-DONE         VALUE 'Y' HIGH-VALUES.",
                "01  WS-HEX          PIC X VALUE X\"FF\".",
            ),
            goldenLines(
                "«Keyword|WORKING-STORAGE» «Keyword|SECTION»«Punctuation|.»",
                "«Number|01»  WS-REC«Punctuation|.»",
                "    «Number|05»  WS-AMOUNT    «Keyword|PIC» «Type|S9(5)V99» «Keyword|COMP-3» «Keyword|VALUE» «Constant|ZERO»«Punctuation|.»",
                "    «Number|05»  WS-NAME      «Keyword|PICTURE» «Keyword|IS» «Type|X(10)» «Keyword|VALUE» «Constant|SPACES»«Punctuation|.»",
                "    «Number|05»  WS-EDIT      «Keyword|PIC» «Type|ZZ,ZZ9.99-»«Punctuation|.»",
                "«Number|77»  WS-QUOTE        «Keyword|PIC» «Type|X(5)» «Keyword|VALUE» «String|\"it»«Constant|\"\"»«String|s\"»«Punctuation|.»",
                "«Number|88»  IS-DONE         «Keyword|VALUE» «String|'Y'» «Constant|HIGH-VALUES»«Punctuation|.»",
                "«Number|01»  WS-HEX          «Keyword|PIC» «Type|X» «Keyword|VALUE» «String|X\"FF\"»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun fixedFormatColumns() {
        assertGolden(
            "cobol",
            goldenLines(
                "000100 IDENTIFICATION DIVISION.                                         PROG01",
                "000200* a comment line in column 7",
                "000300/ a page break comment",
                "000400 PROCEDURE DIVISION.",
                "000500 MAIN.",
                "000600     DISPLAY \"A LITERAL THAT RUNS ON",
                "000700-    \"TO HERE\" X.",
                "000800D    DISPLAY \"DEBUG\".",
                "000900     COMPUTE X = Y ** 2 + 3.5 - Z.                                 TAIL",
                "001000     STOP RUN.",
            ),
            goldenLines(
                "«Number|000100» «Keyword|IDENTIFICATION» «Keyword|DIVISION»«Punctuation|.»                                         «Comment|PROG01»",
                "«Number|000200»«Comment|* a comment line in column 7»",
                "«Number|000300»«Comment|/ a page break comment»",
                "«Number|000400» «Keyword|PROCEDURE» «Keyword|DIVISION»«Punctuation|.»",
                "«Number|000500» «Function|MAIN»«Punctuation|.»",
                "«Number|000600»     «Keyword|DISPLAY» «String|\"A LITERAL THAT RUNS ON»",
                "«Number|000700»«Punctuation|-»    «String|\"TO HERE\"» X«Punctuation|.»",
                "«Number|000800»«Keyword|D»    «Keyword|DISPLAY» «String|\"DEBUG\"»«Punctuation|.»",
                "«Number|000900»     «Keyword|COMPUTE» X «Operator|=» Y «Operator|**» «Number|2» «Operator|+» «Number|3.5» «Operator|-» Z«Punctuation|.»                                «Comment| TAIL»",
                "«Number|001000»     «Keyword|STOP» «Keyword|RUN»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun fixedFormatWithoutSequenceNumbersAndOperandLines() {
        assertGolden(
            "cobol",
            goldenLines(
                "       MAIN-PARA.",
                "           MOVE A TO",
                "               WS-TOTAL.",
                "           EXIT.",
                "      * comment with blank sequence area",
                "       NEXT-PARA. DISPLAY 'X'.",
            ),
            goldenLines(
                "       «Function|MAIN-PARA»«Punctuation|.»",
                "           «Keyword|MOVE» A «Keyword|TO»",
                "               WS-TOTAL«Punctuation|.»",
                "           «Keyword|EXIT»«Punctuation|.»",
                "      «Comment|* comment with blank sequence area»",
                "       «Function|NEXT-PARA»«Punctuation|.» «Keyword|DISPLAY» «String|'X'»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun directivesSwitchTheFormat() {
        assertGolden(
            "cobol",
            goldenLines(
                ">>SOURCE FORMAT IS FREE",
                "000100* not a comment in free format",
                "      \$SET SOURCEFORMAT\"FIXED\"",
                "000200* a comment again",
            ),
            goldenLines(
                "«Keyword|>>SOURCE» FORMAT IS FREE",
                "«Number|000100»«Operator|*» «Keyword|not» a comment «Keyword|in» «Keyword|free» format",
                "      «Keyword|\$SET» SOURCEFORMAT\"FIXED\"",
                "«Number|000200»«Comment|* a comment again»",
            ),
        )
    }

    @Test
    fun unterminatedLiteralsEndWithTheirLine() {
        assertGolden(
            "cobol",
            goldenLines(
                "DISPLAY \"open",
                "DISPLAY 'x' *> 'done",
            ),
            goldenLines(
                "«Keyword|DISPLAY» «String|\"open»",
                "«Keyword|DISPLAY» «String|'x'» «Comment|*> 'done»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "cobol",
            "DISPLAY \"😀\".\r\n000100* é\r\nMOVE \uD83D TO X.\r\n",
            goldenLines(
                "«Keyword|DISPLAY» «String|\"😀\"»«Punctuation|.»␍",
                "«Number|000100»«Comment|* é»␍",
                "«Keyword|MOVE» ⟨D83D⟩ «Keyword|TO» X«Punctuation|.»␍",
                "",
            ),
        )
    }

    @Test
    fun aLineCutByTheCapStopsBeforeTheIdentificationArea() {
        assertGolden(
            "cobol",
            goldenLines(
                "000100 MOVE 1 TO WS-COUNT.                                        TAIL",
            ),
            goldenLines(
                "«Number|000100» «Keyword|MOVE» «Number|1» «Keyword|TO» WS-COUNT«Punctuation|.»                                        TAIL",
            ),
            HighlightLimits(maxLineLength = 30),
        )
    }
}
