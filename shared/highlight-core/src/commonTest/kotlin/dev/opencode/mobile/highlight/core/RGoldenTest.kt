package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written R fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class RGoldenTest {
    @Test
    fun functionsAssignmentsAndOperators() {
        assertGolden(
            "r",
            goldenLines(
                "label <- function(count, sep = \", \") {",
                "  # format a label",
                "  paste0(\"sessions: \", count)",
                "}",
                "x <- c(1L, 2.5, 0x1F, 1e-3, 2i, TRUE, NA, NULL, Inf)",
                "df\$col[df\$col > 0] <- NA_real_",
                "result <- data |> dplyr::filter(x %in% y) %>% head()",
                "f <- \\(a, b) a + b",
                "obj@slot -> y",
                "if (is.na(x)) stop(\"bad\") else NULL",
            ),
            goldenLines(
                "label «Operator|<-» «Keyword|function»«Punctuation|(»«Variable|count»«Punctuation|,» «Variable|sep» «Operator|=» «String|\", \"»«Punctuation|)» «Punctuation|{»",
                "  «Comment|# format a label»",
                "  «Function|paste0»«Punctuation|(»«String|\"sessions: \"»«Punctuation|,» count«Punctuation|)»",
                "«Punctuation|}»",
                "x «Operator|<-» «Function|c»«Punctuation|(»«Number|1L»«Punctuation|,» «Number|2.5»«Punctuation|,» «Number|0x1F»«Punctuation|,» «Number|1e-3»«Punctuation|,» «Number|2i»«Punctuation|,» «Constant|TRUE»«Punctuation|,» «Constant|NA»«Punctuation|,» «Constant|NULL»«Punctuation|,» «Constant|Inf»«Punctuation|)»",
                "df«Operator|\$»«Property|col»«Punctuation|[»df«Operator|\$»«Property|col» «Operator|>» «Number|0»«Punctuation|]» «Operator|<-» «Constant|NA_real_»",
                "result «Operator|<-» data «Operator||>» «Type|dplyr»«Operator|::»«Function|filter»«Punctuation|(»x «Operator|%in%» y«Punctuation|)» «Operator|%>%» «Function|head»«Punctuation|()»",
                "f «Operator|<-» «Keyword|\\»«Punctuation|(»«Variable|a»«Punctuation|,» «Variable|b»«Punctuation|)» a «Operator|+» b",
                "obj«Operator|@»«Property|slot» «Operator|->» y",
                "«Keyword|if» «Punctuation|(»«Function|is.na»«Punctuation|(»x«Punctuation|))» «Function|stop»«Punctuation|(»«String|\"bad\"»«Punctuation|)» «Keyword|else» «Constant|NULL»",
            ),
        )
    }

    @Test
    fun stringsRawStringsAndBackticks() {
        assertGolden(
            "r",
            goldenLines(
                "`my var` <- 'single \\'esc\\''",
                "raw <- r\"(C:\\path\\no\\escapes)\"",
                "raw2 <- R\"---[has ]\" inside]---\"",
                "s <- \"multi",
                "line\\t\"",
                "r3 <- r\"{open",
                "still } inside",
                "}\"",
                "`if`(TRUE, 1)",
            ),
            goldenLines(
                "`my var` «Operator|<-» «String|'single »«Constant|\\'»«String|esc»«Constant|\\'»«String|'»",
                "raw «Operator|<-» «String|r\"(C:\\path\\no\\escapes)\"»",
                "raw2 «Operator|<-» «String|R\"---[has ]\" inside]---\"»",
                "s «Operator|<-» «String|\"multi»",
                "«String|line»«Constant|\\t»«String|\"»",
                "r3 «Operator|<-» «String|r\"{open»",
                "«String|still } inside»",
                "«String|}\"»",
                "«Function|`if`»«Punctuation|(»«Constant|TRUE»«Punctuation|,» «Number|1»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun parametersAndNamespaces() {
        assertGolden(
            "r",
            goldenLines(
                "f <- function(x = c(1, 2), ...) {",
                "  x[[1]] + .5",
                "}",
                "m <- matrix(1:4, nrow = 2) %*% t(m)",
                "library(ggplot2); ggplot2:::fn",
            ),
            goldenLines(
                "f «Operator|<-» «Keyword|function»«Punctuation|(»«Variable|x» «Operator|=» «Function|c»«Punctuation|(»«Number|1»«Punctuation|,» «Number|2»«Punctuation|),» «Variable|...»«Punctuation|)» «Punctuation|{»",
                "  x«Punctuation|[[»«Number|1»«Punctuation|]]» «Operator|+» «Number|.5»",
                "«Punctuation|}»",
                "m «Operator|<-» «Function|matrix»«Punctuation|(»«Number|1»«Operator|:»«Number|4»«Punctuation|,» nrow «Operator|=» «Number|2»«Punctuation|)» «Operator|%*%» «Function|t»«Punctuation|(»m«Punctuation|)»",
                "«Function|library»«Punctuation|(»ggplot2«Punctuation|);» «Type|ggplot2»«Operator|:::»fn",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "r",
            "x <- \"😀\"\r\n# \uD83D\r\ny <- \uD83D\r\ns <- 'open\r\n",
            goldenLines(
                "x «Operator|<-» «String|\"😀\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "y «Operator|<-» ⟨D83D⟩␍",
                "s «Operator|<-» «String|'open»␍",
                "",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "r",
            goldenLines(
                "s <- \"abc def\"",
                "print(s)",
            ),
            goldenLines(
                "s «Operator|<-» «String|\"abc de»f\"",
                "«String|print(s)»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
