package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Python fixtures: the contract for the Python lexer. Inputs use raw strings, so a
 * `\n` there is a Python escape; `$Q3` stands for `"""`, which a Kotlin raw string cannot hold.
 */
class PythonGoldenTest {
    @Test
    fun importsClassesFunctionsAndNames() {
        assertGolden(
            "python",
            """
            import os
            from . import x as y  # note

            @dataclass(frozen=True)
            class Store(Base, Generic[T]):
                ${Q3}Doc.$Q3
                def __init__(self, n: int = 0) -> None:
                    self.items = dict()
                    print(len(self.items), MAX_SIZE, __name__)
                    return self.items.get(n)
            """.trimIndent(),
            """
            «Keyword|import» os
            «Keyword|from» «Punctuation|.» «Keyword|import» x «Keyword|as» y  «Comment|# note»

            «Function|@dataclass»«Punctuation|(»frozen«Operator|=»«Constant|True»«Punctuation|)»
            «Keyword|class» «Type|Store»«Punctuation|(»«Type|Base»«Punctuation|,» «Type|Generic»«Punctuation|[»«Type|T»«Punctuation|]):»
                «String|${Q3}Doc.$Q3»
                «Keyword|def» «Function|__init__»«Punctuation|(»«Variable|self»«Punctuation|,» n«Punctuation|:» «Type|int» «Operator|=» «Number|0»«Punctuation|)» «Punctuation|->» «Constant|None»«Punctuation|:»
                    «Variable|self»«Punctuation|.»«Property|items» «Operator|=» «Type|dict»«Punctuation|()»
                    «Function|print»«Punctuation|(»«Function|len»«Punctuation|(»«Variable|self»«Punctuation|.»«Property|items»«Punctuation|),» «Constant|MAX_SIZE»«Punctuation|,» «Variable|__name__»«Punctuation|)»
                    «Keyword|return» «Variable|self»«Punctuation|.»«Property|items»«Punctuation|.»«Function|get»«Punctuation|(»n«Punctuation|)»
            """.trimIndent(),
        )
    }

    @Test
    fun decoratorsAsyncAndOperatorWords() {
        assertGolden(
            "py",
            """
                @app.route("/x")
            async def handler(*args, **kw):
                if a and not b or c is None and d in e:
                    await run(x)
            """.trimIndent(),
            """
                «Function|@app.route»«Punctuation|(»«String|"/x"»«Punctuation|)»
            «Keyword|async» «Keyword|def» «Function|handler»«Punctuation|(»«Operator|*»args«Punctuation|,» «Operator|**»kw«Punctuation|):»
                «Keyword|if» a «Operator|and» «Operator|not» b «Operator|or» c «Operator|is» «Constant|None» «Operator|and» d «Operator|in» e«Punctuation|:»
                    «Keyword|await» «Function|run»«Punctuation|(»x«Punctuation|)»
            """.trimIndent(),
        )
    }

    @Test
    fun stringPrefixesAndEscapes() {
        assertGolden(
            "python",
            """s = 'a\n' "b\x41\q" r'\d+\'' b"\101" Rb'x' F"y" rf'{x}\n' u'z' tR"{t}" BR"\"" fR'' """,
            """s «Operator|=» «String|'a»«Constant|\n»«String|'» «String|"b»«Constant|\x41»«String|\q"» «Keyword|r»«String|'\d+\''» """ +
                """«Keyword|b»«String|"»«Constant|\101»«String|"» «Keyword|Rb»«String|'x'» «Keyword|F»«String|"y"» """ +
                """«Keyword|rf»«String|'»«Constant|{»x«Constant|}»«String|\n'» «Keyword|u»«String|'z'» «Keyword|tR»«String|"»«Constant|{»t«Constant|}»«String|"» """ +
                """«Keyword|BR»«String|"\""» «Keyword|fR»«String|''» """,
        )
    }

    @Test
    fun namesThatAreNotPrefixesStayNames() {
        // `ur`, `bu`, and longer words before a quote are names followed by a string.
        assertGolden(
            "python",
            """ur'x' bu"y" fx'z' print'w'""",
            """ur«String|'x'» bu«String|"y"» fx«String|'z'» «Function|print»«String|'w'»""",
        )
    }

    @Test
    fun unicodeAndNamedEscapes() {
        assertGolden(
            "python",
            "s = \"\\u00e9\\U0001F600\\u12\\N{EM DASH} \\N{bad\" + f\"\\N{DASH}{x}\" + '\\0\\777\\8'",
            "s «Operator|=» «String|\"»«Constant|\\u00e9\\U0001F600\\u»«String|12»«Constant|\\N{EM DASH}»«String| »«Constant|\\N»«String|{bad\"» " +
                "«Operator|+» «Keyword|f»«String|\"»«Constant|\\N{DASH}{»x«Constant|}»«String|\"» «Operator|+» «String|'»«Constant|\\0\\777»«String|\\8'»",
        )
    }

    @Test
    fun fStringFieldsConversionsAndSpecs() {
        assertGolden(
            "python",
            """f"a{{b}} {x!r:>{width}.2f} {d['k']} {y=} {a!=b} {'%d' % 3} {now:%Y-%m-%d} {m[0]:{w}}" """,
            """«Keyword|f»«String|"a»«Constant|{{»«String|b»«Constant|}}»«String| »«Constant|{»x«Keyword|!r:>»«Constant|{»width«Constant|}»""" +
                """«Keyword|.2f»«Constant|}»«String| »«Constant|{»d«Punctuation|[»«String|'k'»«Punctuation|]»«Constant|}»«String| »""" +
                """«Constant|{»y«Operator|=»«Constant|}»«String| »«Constant|{»a«Operator|!=»b«Constant|}»«String| »""" +
                """«Constant|{»«String|'%d'» «Operator|%» «Number|3»«Constant|}»«String| »«Constant|{»now«Keyword|:%Y-%m-%d»«Constant|}»«String| »""" +
                """«Constant|{»m«Punctuation|[»«Number|0»«Punctuation|]»«Keyword|:»«Constant|{»w«Constant|}}»«String|"» """,
        )
    }

    @Test
    fun bracketsInsideAFieldHideColonsAndBraces() {
        assertGolden(
            "python",
            """f"{ {'a': 1}['a'] } {(lambda v: v)(2)} {x:=5}" '}' """,
            """«Keyword|f»«String|"»«Constant|{» «Punctuation|{»«String|'a'»«Punctuation|:» «Number|1»«Punctuation|}[»«String|'a'»«Punctuation|]» """ +
                """«Constant|}»«String| »«Constant|{»«Punctuation|(»«Keyword|lambda» v«Punctuation|:» v«Punctuation|)(»«Number|2»«Punctuation|)»""" +
                """«Constant|}»«String| »«Constant|{»x«Keyword|:=5»«Constant|}»«String|"» «String|'}'» """,
        )
    }

    @Test
    fun tripleQuotedStringsAndFStringsAcrossLines() {
        assertGolden(
            "python",
            """
            x = f'''
            line {value
              + 1} {{lit}} {n:>{
            w}}
            end'''
            doc = $Q3
              It's "quoted" \t # not a comment
            $Q3
            after = 1
            """.trimIndent(),
            """
            x «Operator|=» «Keyword|f»«String|'''»
            «String|line »«Constant|{»value
              «Operator|+» «Number|1»«Constant|}»«String| »«Constant|{{»«String|lit»«Constant|}}»«String| »«Constant|{»n«Keyword|:>»«Constant|{»
            w«Constant|}}»
            «String|end'''»
            doc «Operator|=» «String|$Q3»
            «String|  It's "quoted" »«Constant|\t»«String| # not a comment»
            «String|$Q3»
            after «Operator|=» «Number|1»
            """.trimIndent(),
        )
    }

    @Test
    fun quotesInsideTripleStringsAndRunsOfQuotes() {
        assertGolden(
            "python",
            """
            a = '''it''s '' still'''' b = ""
            c = '''''' + r'''\'''' + 'x'
            """.trimIndent(),
            """
            a «Operator|=» «String|'''it''s '' still'''' b = ""»
            c «Operator|=» «String|''''''» «Operator|+» «Keyword|r»«String|'''\''''» «Operator|+» «String|'x'»
            """.trimIndent(),
        )
    }

    @Test
    fun numbers() {
        assertGolden(
            "python",
            "n = [0x1F, 0XdeadBEEF, 0o17, 0b1010_1010, 1_000, 3.14, .5, 1e-3, 2.5E+10j, 7J, 1., 0_0, 1e, 1if x else 2]",
            "n «Operator|=» «Punctuation|[»«Number|0x1F»«Punctuation|,» «Number|0XdeadBEEF»«Punctuation|,» «Number|0o17»«Punctuation|,» " +
                "«Number|0b1010_1010»«Punctuation|,» «Number|1_000»«Punctuation|,» «Number|3.14»«Punctuation|,» «Number|.5»«Punctuation|,» " +
                "«Number|1e-3»«Punctuation|,» «Number|2.5E+10j»«Punctuation|,» «Number|7J»«Punctuation|,» «Number|1.»«Punctuation|,» " +
                "«Number|0_0»«Punctuation|,» «Number|1»e«Punctuation|,» «Number|1»«Keyword|if» x «Keyword|else» «Number|2»«Punctuation|]»",
        )
    }

    @Test
    fun softKeywordsOnlyWhereTheyStartAStatement() {
        assertGolden(
            "python",
            """
            match command.split():  # dispatch
                case ["go", direction] if direction:
                    pass
                case Point(x=0) | _:
                    match = _
            match(x)
            match (x):
            print(match, case)
            case: int = 1
            type Point = tuple[float, float]
            type Pair[T] = tuple[T, T]
            type = 3
            type(x) == type
            match x:  # "trailing:" comment
            match "a:b"
            """.trimIndent(),
            """
            «Keyword|match» command«Punctuation|.»«Function|split»«Punctuation|():»  «Comment|# dispatch»
                «Keyword|case» «Punctuation|[»«String|"go"»«Punctuation|,» direction«Punctuation|]» «Keyword|if» direction«Punctuation|:»
                    «Keyword|pass»
                «Keyword|case» «Function|Point»«Punctuation|(»x«Operator|=»«Number|0»«Punctuation|)» «Operator||» «Keyword|_»«Punctuation|:»
                    match «Operator|=» _
            «Function|match»«Punctuation|(»x«Punctuation|)»
            «Keyword|match» «Punctuation|(»x«Punctuation|):»
            «Function|print»«Punctuation|(»match«Punctuation|,» case«Punctuation|)»
            case«Punctuation|:» «Type|int» «Operator|=» «Number|1»
            «Keyword|type» «Type|Point» «Operator|=» «Type|tuple»«Punctuation|[»«Type|float»«Punctuation|,» «Type|float»«Punctuation|]»
            «Keyword|type» «Type|Pair»«Punctuation|[»«Type|T»«Punctuation|]» «Operator|=» «Type|tuple»«Punctuation|[»«Type|T»«Punctuation|,» «Type|T»«Punctuation|]»
            «Type|type» «Operator|=» «Number|3»
            «Type|type»«Punctuation|(»x«Punctuation|)» «Operator|==» «Type|type»
            «Keyword|match» x«Punctuation|:»  «Comment|# "trailing:" comment»
            match «String|"a:b"»
            """.trimIndent(),
        )
    }

    @Test
    fun operatorsAndPunctuation() {
        assertGolden(
            "python",
            "a := 1; b @= c @ d; x -> y; ... ; lambda: x ** 2 // 3 != ~4 << 1 >>= 2 % 5 ^ 6 & 7 | 8 \\",
            "a «Operator|:=» «Number|1»«Punctuation|;» b «Operator|@=» c «Operator|@» d«Punctuation|;» x «Punctuation|->» y«Punctuation|;» " +
                "«Constant|...» «Punctuation|;» «Keyword|lambda»«Punctuation|:» x «Operator|**» «Number|2» «Operator|//» «Number|3» «Operator|!=» " +
                "«Operator|~»«Number|4» «Operator|<<» «Number|1» «Operator|>>=» «Number|2» «Operator|%» «Number|5» «Operator|^» «Number|6» " +
                "«Operator|&» «Number|7» «Operator||» «Number|8» \\",
        )
    }

    @Test
    fun commentsAndStringsDoNotLeakIntoEachOther() {
        assertGolden(
            "python",
            """
            # "not a string
            s = "# not a comment"  # 'still' a comment
            t = 'it"s'
            """.trimIndent(),
            """
            «Comment|# "not a string»
            s «Operator|=» «String|"# not a comment"»  «Comment|# 'still' a comment»
            t «Operator|=» «String|'it"s'»
            """.trimIndent(),
        )
    }

    @Test
    fun unterminatedStringsEndAtTheirLineAndTripleQuotesRunToTheEnd() {
        assertGolden(
            "python",
            """
            s = "unterminated
            n = f'{x
            next = 1
            t = '''open
            still {not} a field
            """.trimIndent(),
            """
            s «Operator|=» «String|"unterminated»
            n «Operator|=» «Keyword|f»«String|'»«Constant|{»x
            «Function|next» «Operator|=» «Number|1»
            t «Operator|=» «String|'''open»
            «String|still {not} a field»
            """.trimIndent(),
        )
    }

    @Test
    fun backslashContinuesAOneLineStringAndOpenBracketsContinueAField() {
        // A field without an open bracket ends at its line (it heals a typo like f"{x" at once);
        // a field inside brackets continues, the multi-line form of Python 3.12.
        assertGolden(
            "python",
            """
            x = 'a\
            continued'
            y = f"{
              z }"
            w = f"{', '.join([
                'a',  # one
            ])}" + 1
            """.trimIndent(),
            """
            x «Operator|=» «String|'a\»
            «String|continued'»
            y «Operator|=» «Keyword|f»«String|"»«Constant|{»
              z «Punctuation|}»«String|"»
            w «Operator|=» «Keyword|f»«String|"»«Constant|{»«String|', '»«Punctuation|.»«Function|join»«Punctuation|([»
                «String|'a'»«Punctuation|,»  «Comment|# one»
            «Punctuation|])»«Constant|}»«String|"» «Operator|+» «Number|1»
            """.trimIndent(),
        )
    }

    @Test
    fun deepFStringNesting() {
        // Three levels keep their fields; a string opened at the fourth colors to its close.
        assertGolden(
            "python",
            "f\"{f'{f\"{x}\"}'}\" + f'{f\"{f'''{f\"\"\"{deep}\"\"\"}'''}\"}'\nok = 1",
            "«Keyword|f»«String|\"»«Constant|{»«Keyword|f»«String|'»«Constant|{»«Keyword|f»«String|\"»«Constant|{»x«Constant|}»«String|\"»" +
                "«Constant|}»«String|'»«Constant|}»«String|\"» «Operator|+» «Keyword|f»«String|'»«Constant|{»«Keyword|f»«String|\"»«Constant|{»" +
                "«Keyword|f»«String|'''»«Constant|{»«Keyword|f»«String|\"\"\"{deep}\"\"\"»«Constant|}»«String|'''»«Constant|}»«String|\"»" +
                "«Constant|}»«String|'»\nok «Operator|=» «Number|1»",
        )
    }

    @Test
    fun deepNestingAcrossLinesInTripleFStrings() {
        assertGolden(
            "python",
            "a = f'''{f\"\"\"{f'''\n{x}\n'''}\"\"\"}'''\nok = 1",
            "a «Operator|=» «Keyword|f»«String|'''»«Constant|{»«Keyword|f»«String|\"\"\"»«Constant|{»«Keyword|f»«String|'''»\n" +
                "«Constant|{»x«Constant|}»\n«String|'''»«Constant|}»«String|\"\"\"»«Constant|}»«String|'''»\nok «Operator|=» «Number|1»",
        )
    }

    @Test
    fun crlfLinesLexLikeLfLines() {
        assertGolden(
            "python",
            "x = 1\r\ny = '''a\r\nb'''\r\nz = 'c\\\r\nd'\r\n",
            "x «Operator|=» «Number|1»␍\ny «Operator|=» «String|'''a»␍\n«String|b'''»␍\nz «Operator|=» «String|'c\\»␍\n«String|d'»␍\n",
        )
    }

    @Test
    fun emojiZeroWidthLoneSurrogatesAndUnicodeNames() {
        assertGolden(
            "python",
            "a = \"😀\uD83D x\u200B\"\n\uDE00 = 1  # 😀\nnaïve = Ñandú(ñ)\n",
            "a «Operator|=» «String|\"😀⟨D83D⟩ x\u200B\"»\n⟨DE00⟩ «Operator|=» «Number|1»  «Comment|# 😀»\n" +
                "naïve «Operator|=» «Function|Ñandú»«Punctuation|(»ñ«Punctuation|)»\n",
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "python",
            "x = 'abcdefghij'\ns = '''abcdefgh\nok'''",
            "x «Operator|=» «String|'abcde»fghij'\ns «Operator|=» «String|'''abc»defgh\n«String|ok'''»",
            HighlightLimits(maxLineLength = 10),
        )
    }

    @Test
    fun aVeryLongLineColorsUpToTheCap() {
        val source = "x = '" + "a".repeat(100_000) + "'\ny = 1"
        assertGolden(
            "python",
            source,
            "x «Operator|=» «String|'" + "a".repeat(19_995) + "»" + "a".repeat(80_005) + "'\ny «Operator|=» «Number|1»",
        )
    }

    @Test
    fun strayCharactersStayPlain() {
        assertGolden(
            "python",
            "\u0000\u0001 ? $ ` x\u000Cy !",
            "\u0000\u0001 ? $ ` x\u000Cy «Operator|!»",
        )
    }

    private companion object {
        const val Q3 = "\"\"\""
    }
}
