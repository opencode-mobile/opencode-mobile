package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written ABAP fixtures: the contract for its lexer. One escaped string per line keeps
 * quotes, `$`, CRLF, tabs, and lone surrogates exact.
 */
class AbapGoldenTest {
    @Test
    fun declarationsAndChainedStatements() {
        assertGolden(
            "abap",
            goldenLines(
                "* a comment in column 1",
                "REPORT zsessions.",
                "DATA count TYPE i VALUE 3. \" trailing comment",
                "WRITE: / 'sessions:', count.",
                "DATA: lv_name TYPE string,",
                "      lt_rows TYPE STANDARD TABLE OF ty_row WITH DEFAULT KEY,",
                "      lo_app  TYPE REF TO lcl_app.",
                "TYPES: BEGIN OF ty_row,",
                "         id TYPE i,",
                "       END OF ty_row.",
                "CONSTANTS lines TYPE i VALUE 1.",
                "FIELD-SYMBOLS <row> TYPE ty_row.",
            ),
            goldenLines(
                "«Comment|* a comment in column 1»",
                "«Keyword|REPORT» zsessions«Punctuation|.»",
                "«Keyword|DATA» «Variable|count» «Keyword|TYPE» «Type|i» «Keyword|VALUE» «Number|3»«Punctuation|.» «Comment|\" trailing comment»",
                "«Keyword|WRITE»«Punctuation|:» «Operator|/» «String|'sessions:'»«Punctuation|,» count«Punctuation|.»",
                "«Keyword|DATA»«Punctuation|:» «Variable|lv_name» «Keyword|TYPE» «Type|string»«Punctuation|,»",
                "      «Variable|lt_rows» «Keyword|TYPE» «Keyword|STANDARD» «Keyword|TABLE» «Keyword|OF» «Type|ty_row» «Keyword|WITH» «Keyword|DEFAULT» «Keyword|KEY»«Punctuation|,»",
                "      «Variable|lo_app»  «Keyword|TYPE» «Keyword|REF» «Keyword|TO» «Type|lcl_app»«Punctuation|.»",
                "«Keyword|TYPES»«Punctuation|:» «Keyword|BEGIN» «Keyword|OF» «Type|ty_row»«Punctuation|,»",
                "         id «Keyword|TYPE» «Type|i»«Punctuation|,»",
                "       «Keyword|END» «Keyword|OF» «Type|ty_row»«Punctuation|.»",
                "«Keyword|CONSTANTS» «Variable|lines» «Keyword|TYPE» «Type|i» «Keyword|VALUE» «Number|1»«Punctuation|.»",
                "«Keyword|FIELD-SYMBOLS» «Variable|<row>» «Keyword|TYPE» «Type|ty_row»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun classesMethodsAndCalls() {
        assertGolden(
            "abap",
            goldenLines(
                "CLASS lcl_app DEFINITION FINAL.",
                "  PUBLIC SECTION.",
                "    METHODS run IMPORTING iv_x TYPE i RETURNING VALUE(rv) TYPE string.",
                "ENDCLASS.",
                "CLASS lcl_app IMPLEMENTATION.",
                "  METHOD run.",
                "    rv = lo_obj->get_name( ) && cl_util=>create( )->text && sy-subrc.",
                "    DATA(lt) = VALUE ty_rows( ( id = 1 ) ).",
                "    IF lines( lt_rows ) > 0 AND <row>-id = abap_true. ENDIF.",
                "    SELECT * FROM /abc/rows INTO TABLE @DATA(rows) WHERE id = @iv_x ##NO_TEXT.",
                "    PERFORM write_log USING rv.",
                "  ENDMETHOD.",
                "ENDCLASS.",
            ),
            goldenLines(
                "«Keyword|CLASS» «Type|lcl_app» «Keyword|DEFINITION» «Keyword|FINAL»«Punctuation|.»",
                "  «Keyword|PUBLIC» «Keyword|SECTION»«Punctuation|.»",
                "    «Keyword|METHODS» «Function|run» «Keyword|IMPORTING» iv_x «Keyword|TYPE» «Type|i» «Keyword|RETURNING» «Keyword|VALUE»«Punctuation|(»rv«Punctuation|)» «Keyword|TYPE» «Type|string»«Punctuation|.»",
                "«Keyword|ENDCLASS»«Punctuation|.»",
                "«Keyword|CLASS» «Type|lcl_app» «Keyword|IMPLEMENTATION»«Punctuation|.»",
                "  «Keyword|METHOD» «Function|run»«Punctuation|.»",
                "    rv «Operator|=» lo_obj«Operator|->»«Function|get_name»«Punctuation|(» «Punctuation|)» «Operator|&&» cl_util«Operator|=>»«Function|create»«Punctuation|(» «Punctuation|)»«Operator|->»«Property|text» «Operator|&&» sy«Punctuation|-»«Property|subrc»«Punctuation|.»",
                "    «Keyword|DATA»«Punctuation|(»«Variable|lt»«Punctuation|)» «Operator|=» «Keyword|VALUE» «Type|ty_rows»«Punctuation|(» «Punctuation|(» id «Operator|=» «Number|1» «Punctuation|)» «Punctuation|).»",
                "    «Keyword|IF» «Function|lines»«Punctuation|(» lt_rows «Punctuation|)» «Operator|>» «Number|0» «Keyword|AND» «Variable|<row>»«Punctuation|-»«Property|id» «Operator|=» «Constant|abap_true»«Punctuation|.» «Keyword|ENDIF»«Punctuation|.»",
                "    «Keyword|SELECT» «Operator|*» «Keyword|FROM» /abc/rows «Keyword|INTO» «Keyword|TABLE» «Operator|@»«Keyword|DATA»«Punctuation|(»«Variable|rows»«Punctuation|)» «Keyword|WHERE» id «Operator|=» «Variable|@iv_x» «Attribute|##NO_TEXT»«Punctuation|.»",
                "    «Keyword|PERFORM» «Function|write_log» «Keyword|USING» rv«Punctuation|.»",
                "  «Keyword|ENDMETHOD»«Punctuation|.»",
                "«Keyword|ENDCLASS»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun literalsAndStringTemplates() {
        assertGolden(
            "abap",
            goldenLines(
                "lv = 'it''s' && `back``tick` && 42.",
                "lv = |Hello { lv_name WIDTH = 10 } \\| \\{ \\n done|.",
                "lv = |a { cond #( when x = 1",
                "                  then 'y' ) } b|.",
                "lv = |open template",
                "lv = 'open literal",
            ),
            goldenLines(
                "lv «Operator|=» «String|'it»«Constant|''»«String|s'» «Operator|&&» «String|`back»«Constant|``»«String|tick`» «Operator|&&» «Number|42»«Punctuation|.»",
                "lv «Operator|=» «String||Hello »«Punctuation|{» lv_name «Keyword|WIDTH» «Operator|=» «Number|10» «Punctuation|}»«String| »«Constant|\\|»«String| »«Constant|\\{»«String| »«Constant|\\n»«String| done|»«Punctuation|.»",
                "lv «Operator|=» «String||a »«Punctuation|{» «Keyword|cond» «Operator|#»«Punctuation|(» «Keyword|when» x «Operator|=» «Number|1»",
                "                  «Keyword|then» «String|'y'» «Punctuation|)» «Punctuation|}»«String| b|»«Punctuation|.»",
                "lv «Operator|=» «String||open template»",
                "lv «Operator|=» «String|'open literal»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "abap",
            "lv = '😀'. \" é\r\n* c\r\nlv = \uD83D.\r\n",
            goldenLines(
                "lv «Operator|=» «String|'😀'»«Punctuation|.» «Comment|\" é»␍",
                "«Comment|* c»␍",
                "lv «Operator|=» ⟨D83D⟩«Punctuation|.»␍",
                "",
            ),
        )
    }

    @Test
    fun aTemplateHoleCutByTheCapStaysOpen() {
        assertGolden(
            "abap",
            goldenLines(
                "lv = |a { cond #( abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij",
                "  ) } b|.",
            ),
            goldenLines(
                "lv «Operator|=» «String||a »«Punctuation|{» «Keyword|cond» «Operator|#»«Punctuation|(» abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij",
                "  «Punctuation|)» «Punctuation|}»«String| b|»«Punctuation|.»",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }
}
