package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written SAS fixtures: the contract for its lexer. One escaped string per line keeps quotes,
 * `$`, CRLF, tabs, and lone surrogates exact.
 */
class SasGoldenTest {
    @Test
    fun dataAndProcSteps() {
        assertGolden(
            "sas",
            goldenLines(
                "/* sessions */",
                "data sessions;",
                "  set sashelp.class(keep=name age);",
                "  count = 3;",
                "  label = cats(\"sessions: \", count);",
                "  if age > 12 then output;",
                "run;",
                "proc sort data=work.sessions out=work.sorted;",
                "  by descending age;",
                "run;",
                "proc sql;",
                "  select count(*) from work.sorted where age > 12;",
                "quit;",
            ),
            goldenLines(
                "«Comment|/* sessions */»",
                "«Keyword|data» sessions«Punctuation|;»",
                "  «Keyword|set» sashelp«Punctuation|.»class«Punctuation|(»keep«Operator|=»name age«Punctuation|);»",
                "  count «Operator|=» «Number|3»«Punctuation|;»",
                "  label «Operator|=» «Function|cats»«Punctuation|(»«String|\"sessions: \"»«Punctuation|,» count«Punctuation|);»",
                "  «Keyword|if» age «Operator|>» «Number|12» «Keyword|then» «Keyword|output»«Punctuation|;»",
                "«Keyword|run»«Punctuation|;»",
                "«Keyword|proc» «Function|sort» data«Operator|=»work«Punctuation|.»sessions out«Operator|=»work«Punctuation|.»sorted«Punctuation|;»",
                "  «Keyword|by» «Keyword|descending» age«Punctuation|;»",
                "«Keyword|run»«Punctuation|;»",
                "«Keyword|proc» «Function|sql»«Punctuation|;»",
                "  «Keyword|select» «Function|count»«Punctuation|(»«Operator|*»«Punctuation|)» «Keyword|from» work«Punctuation|.»sorted «Keyword|where» age «Operator|>» «Number|12»«Punctuation|;»",
                "«Keyword|quit»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun commentsAtStatementStarts() {
        assertGolden(
            "sas",
            goldenLines(
                "* a statement comment",
                "  over two lines;",
                "x = a * b; * another;",
                "%* a macro comment;",
                "y = 2 /* block",
                "   comment */ * 3;",
            ),
            goldenLines(
                "«Comment|* a statement comment»",
                "«Comment|  over two lines;»",
                "x «Operator|=» a «Operator|*» b«Punctuation|;» «Comment|* another;»",
                "«Comment|%* a macro comment;»",
                "y «Operator|=» «Number|2» «Comment|/* block»",
                "«Comment|   comment */» «Operator|*» «Number|3»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun macroLanguage() {
        assertGolden(
            "sas",
            goldenLines(
                "%let name = World;",
                "%macro greet(who);",
                "  %put Hello &who. and &&name &name.x;",
                "  title \"Hi &name. and 'quoted'\";",
                "  %if &who = me %then %do; %end;",
                "%mend greet;",
                "%greet(you)",
                "* a comment right after a call;",
                "x = %sysfunc(today()) * 2;",
            ),
            goldenLines(
                "«Keyword|%let» name «Operator|=» World«Punctuation|;»",
                "«Keyword|%macro» «Function|greet»«Punctuation|(»who«Punctuation|);»",
                "  «Keyword|%put» Hello «Variable|&who.» «Keyword|and» «Variable|&&name» «Variable|&name.»x«Punctuation|;»",
                "  «Keyword|title» «String|\"Hi »«Variable|&name.»«String| and 'quoted'\"»«Punctuation|;»",
                "  «Keyword|%if» «Variable|&who» «Operator|=» me «Keyword|%then» «Keyword|%do»«Punctuation|;» «Keyword|%end»«Punctuation|;»",
                "«Keyword|%mend» greet«Punctuation|;»",
                "«Function|%greet»«Punctuation|(»you«Punctuation|)»",
                "«Comment|* a comment right after a call;»",
                "x «Operator|=» «Function|%sysfunc»«Punctuation|(»«Function|today»«Punctuation|())» «Operator|*» «Number|2»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun literalsAndMissingValues() {
        assertGolden(
            "sas",
            goldenLines(
                "d = '01jan2020'd; t = '12:00't; dt = '01jan2020:12:00'dt;",
                "n = 'my var'n; s = 'it''s' || \"say \"\"hi\"\"\";",
                "y = .; z = .A; h = 0FFx; f = 1.5e3; w = 8.;",
                "s = 'open",
                "  still string';",
            ),
            goldenLines(
                "d «Operator|=» «Constant|'01jan2020'd»«Punctuation|;» t «Operator|=» «Constant|'12:00't»«Punctuation|;» dt «Operator|=» «Constant|'01jan2020:12:00'dt»«Punctuation|;»",
                "n «Operator|=» «Variable|'my var'n»«Punctuation|;» s «Operator|=» «String|'it»«Constant|''»«String|s'» «Operator|||» «String|\"say »«Constant|\"\"»«String|hi»«Constant|\"\"»«String|\"»«Punctuation|;»",
                "y «Operator|=» «Number|.»«Punctuation|;» z «Operator|=» «Number|.A»«Punctuation|;» h «Operator|=» «Number|0FFx»«Punctuation|;» f «Operator|=» «Number|1.5e3»«Punctuation|;» w «Operator|=» «Number|8.»«Punctuation|;»",
                "s «Operator|=» «String|'open»",
                "«String|  still string'»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun datalines() {
        assertGolden(
            "sas",
            goldenLines(
                "data raw;",
                "  input a b;",
                "  datalines;",
                "1 2",
                "3 * 4",
                ";",
                "data raw4; input line \$; cards4;",
                "a;b",
                ";;;;",
                "run;",
            ),
            goldenLines(
                "«Keyword|data» raw«Punctuation|;»",
                "  «Keyword|input» a b«Punctuation|;»",
                "  «Keyword|datalines»«Punctuation|;»",
                "«String|1 2»",
                "«String|3 * 4»",
                "«Punctuation|;»",
                "«Keyword|data» raw4«Punctuation|;» «Keyword|input» line \$«Punctuation|;» «Keyword|cards4»«Punctuation|;»",
                "«String|a;b»",
                "«Punctuation|;;;;»",
                "«Keyword|run»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "sas",
            "x = '😀'; /* é */\r\ny = \uD83D;\r\n",
            goldenLines(
                "x «Operator|=» «String|'😀'»«Punctuation|;» «Comment|/* é */»␍",
                "y «Operator|=» ⟨D83D⟩«Punctuation|;»␍",
                "",
            ),
        )
    }

    @Test
    fun aCommentCutByTheCapStaysOpen() {
        assertGolden(
            "sas",
            goldenLines(
                "x = 1; /* abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij */",
                "still comment */ y = 2;",
            ),
            goldenLines(
                "x «Operator|=» «Number|1»«Punctuation|;» «Comment|/* abcdefghijabcd»efghijabcdefghijabcdefghijabcdefghijabcdefghij */",
                "«Comment|still comment */» y «Operator|=» «Number|2»«Punctuation|;»",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }
}
