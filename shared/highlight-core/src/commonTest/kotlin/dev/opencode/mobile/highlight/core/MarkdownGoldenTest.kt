package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Markdown fixtures, fenced code in its language included: the contract for the Markdown lexer. */
class MarkdownGoldenTest {
    @Test
    fun anUnterminatedFenceRunsToTheEnd() {
        assertGolden(
            "markdown",
            """
            ```kotlin
            val a = 1
            # not a heading
            """.trimIndent(),
            """
            «Punctuation|```»kotlin
            «Keyword|val» a «Operator|=» «Number|1»
            # not a heading
            """.trimIndent(),
        )
    }

    @Test
    fun blocksAndInlines() {
        assertGolden(
            "markdown",
            """
            # Title #

            Some *emphasis*, **strong**, `code`, ~~gone~~ and a [link](https://example.com "Title").
            ![img](a.png) and [ref][id] and <https://example.com> and <kbd>K</kbd> &copy; \*not\*
            snake_case_word and 2 * 3 * 4 and ``a ` b``

            - item with `code`
            - [ ] task
              1. nested
            > quote with **bold**

            | a | b |
            |---|:-:|
            | 1 | 2 |

            [id]: https://example.com "T"
            Setext
            ===
            ***
            """.trimIndent(),
            """
            «Punctuation|#» «Keyword|Title» «Punctuation|#»

            Some «Punctuation|*»emphasis«Punctuation|*», «Punctuation|**»strong«Punctuation|**», «Punctuation|`»«String|code»«Punctuation|`», «Punctuation|~~»gone«Punctuation|~~» and a «Punctuation|[»«String|link»«Punctuation|](»https://example.com «String|"Title"»«Punctuation|)».
            «Punctuation|![»«String|img»«Punctuation|](»a.png«Punctuation|)» and «Punctuation|[»«String|ref»«Punctuation|][»«Constant|id»«Punctuation|]» and «Punctuation|<»https://example.com«Punctuation|>» and «Punctuation|<»«Tag|kbd»«Punctuation|>»K«Punctuation|</»«Tag|kbd»«Punctuation|>» «Constant|&copy;» «Constant|\*»not«Constant|\*»
            snake_case_word and 2 * 3 * 4 and «Punctuation|``»«String|a ` b»«Punctuation|``»

            «Punctuation|-» item with «Punctuation|`»«String|code»«Punctuation|`»
            «Punctuation|-» «Punctuation|[ ]» task
              «Punctuation|1.» nested
            «Punctuation|>» quote with «Punctuation|**»bold«Punctuation|**»

            «Punctuation||» a «Punctuation||» b «Punctuation||»
            «Punctuation||---|:-:|»
            «Punctuation||» 1 «Punctuation||» 2 «Punctuation||»

            «Punctuation|[»«Constant|id»«Punctuation|]:» https://example.com «String|"T"»
            Setext
            «Punctuation|===»
            «Punctuation|***»
            """.trimIndent(),
        )
    }

    @Test
    fun fencesEmbedTheirLanguages() {
        assertGolden(
            "markdown",
            """
            ```ts title="x"
            const a: number = `multi
            line`;
            ```

            ~~~~python
            def f(): pass
            ```
            ~~~~

            ```unknown
            plain *text*
            ```

                indented code

            - list
              ```js
              let x = 1;
              ```
            """.trimIndent(),
            """
            «Punctuation|```»ts title="x"
            «Keyword|const» «Variable|a»«Punctuation|:» «Type|number» «Operator|=» «String|`multi»
            «String|line`»«Punctuation|;»
            «Punctuation|```»

            «Punctuation|~~~~»python
            «Keyword|def» «Function|f»«Punctuation|():» «Keyword|pass»
            ```
            «Punctuation|~~~~»

            «Punctuation|```»unknown
            plain *text*
            «Punctuation|```»

                indented code

            «Punctuation|-» list
              «Punctuation|```»js
              «Keyword|let» «Variable|x» «Operator|=» «Number|1»«Punctuation|;»
              «Punctuation|```»
            """.trimIndent(),
        )
    }

    @Test
    fun nestedFencesAndHtmlBlocks() {
        assertGolden(
            "markdown",
            """
            ````markdown
            # Inner
            ```json
            {"a": 1}
            ```
            ````

            <div class="x">
              <script>
              let s = 1;

              </script>
            </div>
            after
            """.trimIndent(),
            """
            «Punctuation|````»markdown
            «Punctuation|#» «Keyword|Inner»
            «Punctuation|```»json
            «Punctuation|{»«Property|"a"»«Punctuation|:» «Number|1»«Punctuation|}»
            «Punctuation|```»
            «Punctuation|````»

            «Punctuation|<»«Tag|div» «Attribute|class»«Punctuation|=»«String|"x"»«Punctuation|>»
              «Punctuation|<»«Tag|script»«Punctuation|>»
              «Keyword|let» «Variable|s» «Operator|=» «Number|1»«Punctuation|;»

              «Punctuation|</»«Tag|script»«Punctuation|>»
            «Punctuation|</»«Tag|div»«Punctuation|>»
            after
            """.trimIndent(),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "markdown",
            "# H\uD83D\uDE00\r\n```js\r\nlet a = '\uD83D';\r\n```\r\n*a* \uDE00",
            "«Punctuation|#» «Keyword|H\uD83D\uDE00»␍\n«Punctuation|```»js␍\n«Keyword|let» «Variable|a» «Operator|=» «String|'⟨D83D⟩'»«Punctuation|;»␍\n«Punctuation|```»␍\n«Punctuation|*»a«Punctuation|*» ⟨DE00⟩",
        )
    }
}
