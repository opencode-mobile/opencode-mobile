package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written CoffeeScript fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class CoffeeScriptGoldenTest {
    @Test
    fun functionsClassesAndInterpolation() {
        assertGolden(
            "coffee",
            goldenLines(
                "# Sessions",
                "label = (count) ->",
                "  \"sessions: #{count} #{@name + \"#{1}\"}\\n\"",
                "class Session extends Base",
                "  constructor: (@name, opts = {}) ->",
                "    super()",
                "  greet: => console.log \"hi #{@name.toUpperCase()}\"",
                "  @create: -> new Session('x')",
                "square = (x) -> x * x",
                "noop = ->",
            ),
            goldenLines(
                "«Comment|# Sessions»",
                "«Function|label» «Operator|=» «Punctuation|(»«Variable|count»«Punctuation|)» «Keyword|->»",
                "  «String|\"sessions: »«Punctuation|#{»count«Punctuation|}»«String| »«Punctuation|#{»«Variable|@name» «Operator|+» «String|\"#{1}\"»«Punctuation|}»«Constant|\\n»«String|\"»",
                "«Keyword|class» «Type|Session» «Keyword|extends» «Type|Base»",
                "  «Function|constructor»«Punctuation|:» «Punctuation|(»«Variable|@name»«Punctuation|,» «Variable|opts» «Operator|=» «Punctuation|{})» «Keyword|->»",
                "    «Keyword|super»«Punctuation|()»",
                "  «Function|greet»«Punctuation|:» «Keyword|=>» console«Punctuation|.»«Property|log» «String|\"hi »«Punctuation|#{»«Variable|@name»«Punctuation|.»«Function|toUpperCase»«Punctuation|()}»«String|\"»",
                "  «Function|@create»«Punctuation|:» «Keyword|->» «Keyword|new» «Function|Session»«Punctuation|(»«String|'x'»«Punctuation|)»",
                "«Function|square» «Operator|=» «Punctuation|(»«Variable|x»«Punctuation|)» «Keyword|->» x «Operator|*» x",
                "«Function|noop» «Operator|=» «Keyword|->»",
            ),
        )
    }

    @Test
    fun stringsRegexesAndHeregexes() {
        assertGolden(
            "coffee",
            goldenLines(
                "html = \"\"\"",
                "  <div class=\"#{cls}\">#{x}</div>",
                "  \"\"\"",
                "plain = '''",
                "  single 'quoted' \\n",
                "  '''",
                "re = /\\d+[/]x/gi",
                "ratio = a / b / c",
                "r = ///",
                "  \\d+  # digits",
                "  #{x}",
                "///g",
                "`var js = 1;`",
            ),
            goldenLines(
                "html «Operator|=» «String|\"\"\"»",
                "«String|  <div class=\"»«Punctuation|#{»cls«Punctuation|}»«String|\">»«Punctuation|#{»x«Punctuation|}»«String|</div>»",
                "«String|  \"\"\"»",
                "plain «Operator|=» «String|'''»",
                "«String|  single 'quoted' »«Constant|\\n»",
                "«String|  '''»",
                "re «Operator|=» «String|/»«Constant|\\d»«String|+[/]x/gi»",
                "ratio «Operator|=» a «Operator|/» b «Operator|/» c",
                "r «Operator|=» «String|///»",
                "«String|  »«Constant|\\d»«String|+  »«Comment|# digits»",
                "«String|  »«Punctuation|#{»x«Punctuation|}»",
                "«String|///g»",
                "«String|`var js = 1;`»",
            ),
        )
    }

    @Test
    fun keywordsNumbersAndOperators() {
        assertGolden(
            "coffee",
            goldenLines(
                "num = 0x1F + 1.5e3 + .5 + 10n",
                "yes and no or null? and not undefined",
                "x ?= y ? z",
                "for own key, value of obj when value isnt off",
                "  console.log key unless value is true",
                "list[1..3]; args...; a::b",
            ),
            goldenLines(
                "num «Operator|=» «Number|0x1F» «Operator|+» «Number|1.5e3» «Operator|+» «Number|.5» «Operator|+» «Number|10n»",
                "«Constant|yes» «Keyword|and» «Constant|no» «Keyword|or» «Constant|null»«Operator|?» «Keyword|and» «Keyword|not» «Constant|undefined»",
                "x «Operator|?=» y «Operator|?» z",
                "«Keyword|for» «Keyword|own» key«Punctuation|,» value «Keyword|of» obj «Keyword|when» value «Keyword|isnt» «Constant|off»",
                "  console«Punctuation|.»«Property|log» key «Keyword|unless» value «Keyword|is» «Constant|true»",
                "list«Punctuation|[»«Number|1»«Operator|..»«Number|3»«Punctuation|];» args«Operator|...»«Punctuation|;» a«Punctuation|::»b",
            ),
        )
    }

    @Test
    fun blockCommentsAndUnterminated() {
        assertGolden(
            "coffee",
            goldenLines(
                "###",
                "Block comment #{not}",
                "###",
                "#### a line comment",
                "s = \"open #{x",
                "y = 'z'",
            ),
            goldenLines(
                "«Comment|###»",
                "«Comment|Block comment #{not}»",
                "«Comment|###»",
                "«Comment|#### a line comment»",
                "s «Operator|=» «String|\"open »«Punctuation|#{»x",
                "y «Operator|=» «String|'z'»",
            ),
        )
    }

    @Test
    fun theLineCapCutsInsideABlockComment() {
        assertGolden(
            "coffee",
            goldenLines(
                "x = 1 ### comment",
                "still ###",
                "y = 2",
            ),
            goldenLines(
                "x «Operator|=» «Number|1» «Comment|##»# comment",
                "still «Comment|##»#",
                "y «Operator|=» «Number|2»",
            ),
            HighlightLimits(maxLineLength = 8),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "coffee",
            "s = \"😀 #{x}\"\r\n# \uD83D\r\nx = '\uDE00'\r\n",
            goldenLines(
                "s «Operator|=» «String|\"😀 »«Punctuation|#{»x«Punctuation|}»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "x «Operator|=» «String|'⟨DE00⟩'»␍",
                "",
            ),
        )
    }
}
