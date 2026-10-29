package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written AWK fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class AwkGoldenTest {
    @Test
    fun patternsFieldsAndBuiltins() {
        assertGolden(
            "awk",
            goldenLines(
                "BEGIN {",
                "  count = 3",
                "  FS = \":\"; OFS = \"\\t\"",
                "  print \"sessions: \" count",
                "}",
                "/^#/ { next }",
                "\$3 > 100 && \$1 ~ /^[a-z]+\$/ { total += \$3; n++ }",
                "NR % 2 == 0 { printf \"%s %d\\n\", \$NF, length(\$0) / 2 }",
                "function max(a, b) { return a > b ? a : b }",
                "END { for (k in arr) delete arr[k]; print max(total, 1.5e3) > \"/dev/stderr\" }",
                "{ gsub(/[/]x/, \"y\"); \$(NF + 1) = \"z\" } # trailing",
            ),
            goldenLines(
                "«Keyword|BEGIN» «Punctuation|{»",
                "  count «Operator|=» «Number|3»",
                "  «Variable|FS» «Operator|=» «String|\":\"»«Punctuation|;» «Variable|OFS» «Operator|=» «String|\"»«Constant|\\t»«String|\"»",
                "  «Keyword|print» «String|\"sessions: \"» count",
                "«Punctuation|}»",
                "«String|/^#/» «Punctuation|{» «Keyword|next» «Punctuation|}»",
                "«Variable|\$3» «Operator|>» «Number|100» «Operator|&&» «Variable|\$1» «Operator|~» «String|/^[a-z]+\$/» «Punctuation|{» total «Operator|+=» «Variable|\$3»«Punctuation|;» n«Operator|++» «Punctuation|}»",
                "«Variable|NR» «Operator|%» «Number|2» «Operator|==» «Number|0» «Punctuation|{» «Keyword|printf» «String|\"%s %d»«Constant|\\n»«String|\"»«Punctuation|,» «Variable|\$NF»«Punctuation|,» «Function|length»«Punctuation|(»«Variable|\$0»«Punctuation|)» «Operator|/» «Number|2» «Punctuation|}»",
                "«Keyword|function» «Function|max»«Punctuation|(»«Variable|a»«Punctuation|,» «Variable|b»«Punctuation|)» «Punctuation|{» «Keyword|return» a «Operator|>» b «Operator|?» a «Operator|:» b «Punctuation|}»",
                "«Keyword|END» «Punctuation|{» «Keyword|for» «Punctuation|(»k «Keyword|in» arr«Punctuation|)» «Keyword|delete» arr«Punctuation|[»k«Punctuation|];» «Keyword|print» «Function|max»«Punctuation|(»total«Punctuation|,» «Number|1.5e3»«Punctuation|)» «Operator|>» «String|\"/dev/stderr\"» «Punctuation|}»",
                "«Punctuation|{» «Function|gsub»«Punctuation|(»«String|/[/]x/»«Punctuation|,» «String|\"y\"»«Punctuation|);» «Variable|\$»«Punctuation|(»«Variable|NF» «Operator|+» «Number|1»«Punctuation|)» «Operator|=» «String|\"z\"» «Punctuation|}» «Comment|# trailing»",
            ),
        )
    }

    @Test
    fun regexOrDivisionAndContinuedStrings() {
        assertGolden(
            "awk",
            goldenLines(
                "{ s = \"cont\\",
                "nued\" ; x = a / b / c }",
                "{ n = n++ / 2; m = (x) / 2 }",
                "{ s = \"open",
                "\$1 ~ /unterminated",
            ),
            goldenLines(
                "«Punctuation|{» s «Operator|=» «String|\"cont»«Constant|\\»",
                "«String|nued\"» «Punctuation|;» x «Operator|=» a «Operator|/» b «Operator|/» c «Punctuation|}»",
                "«Punctuation|{» n «Operator|=» n«Operator|++» «Operator|/» «Number|2»«Punctuation|;» m «Operator|=» «Punctuation|(»x«Punctuation|)» «Operator|/» «Number|2» «Punctuation|}»",
                "«Punctuation|{» s «Operator|=» «String|\"open»",
                "«Variable|\$1» «Operator|~» «String|/unterminated»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "awk",
            "BEGIN { print \"😀\" }\r\n# \uD83D\r\n{ x = \uD83D }\r\n",
            goldenLines(
                "«Keyword|BEGIN» «Punctuation|{» «Keyword|print» «String|\"😀\"» «Punctuation|}»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "«Punctuation|{» x «Operator|=» ⟨D83D⟩ «Punctuation|}»␍",
                "",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "awk",
            goldenLines(
                "{ s = \"abc def\" }",
                "{ print s }",
            ),
            goldenLines(
                "«Punctuation|{» s «Operator|=» «String|\"abc d»ef\" }",
                "«Punctuation|{» «Keyword|print» s «Punctuation|}»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
