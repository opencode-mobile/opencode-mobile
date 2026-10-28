package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written CSS fixtures, SCSS and Less syntax included: the contract for the CSS lexer. */
class CssGoldenTest {
    @Test
    fun aBareDeclarationListColorsAsDeclarations() {
        assertGolden(
            "css",
            """
            color: red; margin: 0 4px
            """.trimIndent(),
            """
            «Property|color»«Punctuation|:» red«Punctuation|;» «Property|margin»«Punctuation|:» «Number|0» «Number|4»«Keyword|px»
            """.trimIndent(),
        )
    }

    @Test
    fun atRulesNestingAndScss() {
        assertGolden(
            "css",
            """
            @import url("theme.css") screen;
            @media screen and (min-width: 600px) {
              .card { margin: 0 auto; }
            }
            @keyframes spin { from { opacity: 0; } 50% { opacity: .5 } }
            .card {
              &:hover { color: red; }
              ${'$'}gap: 4px; // scss
              margin: #{${'$'}gap} 0;
              @include shadow(2px);
              grid-template-areas:
                "a b"
                "c d";
            }
            a{color:red}b{margin:0}
            """.trimIndent(),
            """
            «Keyword|@import» «Function|url»«Punctuation|(»«String|"theme.css"»«Punctuation|)» screen«Punctuation|;»
            «Keyword|@media» screen «Operator|and» «Punctuation|(»«Property|min-width»«Punctuation|:» «Number|600»«Keyword|px»«Punctuation|)» «Punctuation|{»
              «Punctuation|.»«Attribute|card» «Punctuation|{» «Property|margin»«Punctuation|:» «Number|0» auto«Punctuation|;» «Punctuation|}»
            «Punctuation|}»
            «Keyword|@keyframes» spin «Punctuation|{» «Tag|from» «Punctuation|{» «Property|opacity»«Punctuation|:» «Number|0»«Punctuation|;» «Punctuation|}» «Number|50»«Keyword|%» «Punctuation|{» «Property|opacity»«Punctuation|:» «Number|.5» «Punctuation|}» «Punctuation|}»
            «Punctuation|.»«Attribute|card» «Punctuation|{»
              «Tag|&»«Punctuation|:»«Attribute|hover» «Punctuation|{» «Property|color»«Punctuation|:» red«Punctuation|;» «Punctuation|}»
              «Variable|${'$'}gap»«Punctuation|:» «Number|4»«Keyword|px»«Punctuation|;» «Comment|// scss»
              «Property|margin»«Punctuation|:» «Punctuation|#{»«Variable|${'$'}gap»«Punctuation|}» «Number|0»«Punctuation|;»
              «Keyword|@include» «Function|shadow»«Punctuation|(»«Number|2»«Keyword|px»«Punctuation|);»
              «Property|grid-template-areas»«Punctuation|:»
                «String|"a b"»
                «String|"c d"»«Punctuation|;»
            «Punctuation|}»
            «Tag|a»«Punctuation|{»«Property|color»«Punctuation|:»red«Punctuation|}»«Tag|b»«Punctuation|{»«Property|margin»«Punctuation|:»«Number|0»«Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun multiLineSelectorsAndUnterminatedComments() {
        assertGolden(
            "css",
            """
            h1,
            h2:first-child
            {
              color: #333; /* open
              still comment */ margin: 0;
            }
            :root { --text: #333 }
            p { content: "open
            """.trimIndent(),
            """
            «Tag|h1»«Punctuation|,»
            «Tag|h2»«Punctuation|:»«Attribute|first-child»
            «Punctuation|{»
              «Property|color»«Punctuation|:» «Constant|#333»«Punctuation|;» «Comment|/* open»
            «Comment|  still comment */» «Property|margin»«Punctuation|:» «Number|0»«Punctuation|;»
            «Punctuation|}»
            «Punctuation|:»«Attribute|root» «Punctuation|{» «Variable|--text»«Punctuation|:» «Constant|#333» «Punctuation|}»
            «Tag|p» «Punctuation|{» «Property|content»«Punctuation|:» «String|"open»
            """.trimIndent(),
        )
    }

    @Test
    fun selectorsDeclarationsAndValues() {
        assertGolden(
            "css",
            """
            /* card styles */
            .message > a:hover, #main::before, input[type="text" i] {
              color: var(--text);
              padding: 12px 1.5em -2px;
              background: url(data:image/png;base64,AAA=) no-repeat #fff;
              font-family: "Inter", sans-serif !important;
              width: calc(100% - 2rem);
              content: "\201C";
            }
            """.trimIndent(),
            """
            «Comment|/* card styles */»
            «Punctuation|.»«Attribute|message» «Operator|>» «Tag|a»«Punctuation|:»«Attribute|hover»«Punctuation|,» «Punctuation|#»«Attribute|main»«Punctuation|::»«Attribute|before»«Punctuation|,» «Tag|input»«Punctuation|[»«Attribute|type»«Operator|=»«String|"text"» «Attribute|i»«Punctuation|]» «Punctuation|{»
              «Property|color»«Punctuation|:» «Function|var»«Punctuation|(»«Variable|--text»«Punctuation|);»
              «Property|padding»«Punctuation|:» «Number|12»«Keyword|px» «Number|1.5»«Keyword|em» «Number|-2»«Keyword|px»«Punctuation|;»
              «Property|background»«Punctuation|:» «Function|url»«Punctuation|(»«String|data:image/png;base64,AAA=»«Punctuation|)» no-repeat «Constant|#fff»«Punctuation|;»
              «Property|font-family»«Punctuation|:» «String|"Inter"»«Punctuation|,» sans-serif «Keyword|!important»«Punctuation|;»
              «Property|width»«Punctuation|:» «Function|calc»«Punctuation|(»«Number|100»«Keyword|%» «Operator|-» «Number|2»«Keyword|rem»«Punctuation|);»
              «Property|content»«Punctuation|:» «String|"»«Constant|\201C»«String|"»«Punctuation|;»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "css",
            "a { color: red; }\r\nb::after { content: \"\uD83D\uDE00\uD83D\" }\r\nc { x: 1 }",
            "«Tag|a» «Punctuation|{» «Property|color»«Punctuation|:» red«Punctuation|;» «Punctuation|}»␍\n«Tag|b»«Punctuation|::»«Attribute|after» «Punctuation|{» «Property|content»«Punctuation|:» «String|\"\uD83D\uDE00⟨D83D⟩\"» «Punctuation|}»␍\n«Tag|c» «Punctuation|{» «Property|x»«Punctuation|:» «Number|1» «Punctuation|}»",
        )
    }
}
