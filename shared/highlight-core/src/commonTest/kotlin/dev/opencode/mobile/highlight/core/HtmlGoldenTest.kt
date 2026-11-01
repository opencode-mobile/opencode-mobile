package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written HTML fixtures, embedded script and style content included: the contract for the HTML lexer. */
class HtmlGoldenTest {
    @Test
    fun scriptAndStyleEmbedTheirLanguages() {
        assertGolden(
            "html",
            """
            <style>
              body { margin: 0; }
            </style>
            <script type="module">
              import { x } from "./x.js";
              const a = `multi
            line ${'$'}{x}`;
            </script>
            <script lang="ts">let n: number = 1;</script>
            <script type="application/ld+json">{"a": 1}</script>
            <script type="text/template"><div>{{ x }}</div></script>
            <SCRIPT>var x = "</script>";</SCRIPT>
            """.trimIndent(),
            """
            «Punctuation|<»«Tag|style»«Punctuation|>»
              «Tag|body» «Punctuation|{» «Property|margin»«Punctuation|:» «Number|0»«Punctuation|;» «Punctuation|}»
            «Punctuation|</»«Tag|style»«Punctuation|>»
            «Punctuation|<»«Tag|script» «Attribute|type»«Punctuation|=»«String|"module"»«Punctuation|>»
              «Keyword|import» «Punctuation|{» «Variable|x» «Punctuation|}» «Keyword|from» «String|"./x.js"»«Punctuation|;»
              «Keyword|const» «Variable|a» «Operator|=» «String|`multi»
            «String|line »«Punctuation|${'$'}{»«Variable|x»«Punctuation|}»«String|`»«Punctuation|;»
            «Punctuation|</»«Tag|script»«Punctuation|>»
            «Punctuation|<»«Tag|script» «Attribute|lang»«Punctuation|=»«String|"ts"»«Punctuation|>»«Keyword|let» «Variable|n»«Punctuation|:» «Type|number» «Operator|=» «Number|1»«Punctuation|;</»«Tag|script»«Punctuation|>»
            «Punctuation|<»«Tag|script» «Attribute|type»«Punctuation|=»«String|"application/ld+json"»«Punctuation|>{»«Property|"a"»«Punctuation|:» «Number|1»«Punctuation|}</»«Tag|script»«Punctuation|>»
            «Punctuation|<»«Tag|script» «Attribute|type»«Punctuation|=»«String|"text/template"»«Punctuation|>»<div>{{ x }}</div>«Punctuation|</»«Tag|script»«Punctuation|>»
            «Punctuation|<»«Tag|SCRIPT»«Punctuation|>»«Keyword|var» «Variable|x» «Operator|=» «String|"»«Punctuation|</»«Tag|script»«Punctuation|>»";«Punctuation|</»«Tag|SCRIPT»«Punctuation|>»
            """.trimIndent(),
        )
    }

    @Test
    fun structureAttributesAndEntities() {
        assertGolden(
            "html",
            """
            <!DOCTYPE html>
            <html lang="en">
            <body class="main" data-x='y' hidden onclick=go()>
              <!-- a
              comment -->
              <p>a < b &copy; &#169; &#xA9; &nope</p>
              <img src="a.png?x=1&amp;y=2"
                   alt="multi
            line" />
              <br/>
            </body>
            </html>
            """.trimIndent(),
            """
            «Punctuation|<!»«Keyword|DOCTYPE» html«Punctuation|>»
            «Punctuation|<»«Tag|html» «Attribute|lang»«Punctuation|=»«String|"en"»«Punctuation|>»
            «Punctuation|<»«Tag|body» «Attribute|class»«Punctuation|=»«String|"main"» «Attribute|data-x»«Punctuation|=»«String|'y'» «Attribute|hidden» «Attribute|onclick»«Punctuation|=»«String|go()»«Punctuation|>»
              «Comment|<!-- a»
            «Comment|  comment -->»
              «Punctuation|<»«Tag|p»«Punctuation|>»a < b «Constant|&copy;» «Constant|&#169;» «Constant|&#xA9;» &nope«Punctuation|</»«Tag|p»«Punctuation|>»
              «Punctuation|<»«Tag|img» «Attribute|src»«Punctuation|=»«String|"a.png?x=1»«Constant|&amp;»«String|y=2"»
                   «Attribute|alt»«Punctuation|=»«String|"multi»
            «String|line"» «Punctuation|/>»
              «Punctuation|<»«Tag|br»«Punctuation|/>»
            «Punctuation|</»«Tag|body»«Punctuation|>»
            «Punctuation|</»«Tag|html»«Punctuation|>»
            """.trimIndent(),
        )
    }

    @Test
    fun unterminatedTagsAndComments() {
        assertGolden(
            "html",
            """
            <div class="a
            <!-- never closed
            <p>
            """.trimIndent(),
            """
            «Punctuation|<»«Tag|div» «Attribute|class»«Punctuation|=»«String|"a»
            «String|<!-- never closed»
            «String|<p>»
            """.trimIndent(),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "html",
            "<p title=\"\uD83D\uDE00\">\uD83D x</p>\r\n<script>\r\nlet a = `t\r\n\${b}`;\r\n</script>",
            "«Punctuation|<»«Tag|p» «Attribute|title»«Punctuation|=»«String|\"\uD83D\uDE00\"»«Punctuation|>»⟨D83D⟩ x«Punctuation|</»«Tag|p»«Punctuation|>»␍\n«Punctuation|<»«Tag|script»«Punctuation|>»␍\n«Keyword|let» «Variable|a» «Operator|=» «String|`t»␍\n«Punctuation|\${»«Variable|b»«Punctuation|}»«String|`»«Punctuation|;»␍\n«Punctuation|</»«Tag|script»«Punctuation|>»",
        )
    }
}
