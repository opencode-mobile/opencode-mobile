package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Vue fixtures: the contract for [VueLexer]. One escaped string per line keeps `$`, CRLF, and lone surrogates exact. */
class VueGoldenTest {
    @Test
    fun templateScriptAndStyleOfAComponent() {
        assertGolden(
            "vue",
            goldenLines(
                "<script setup lang=\"ts\">",
                "const title: string = \"OpenCode\"",
                "</script>",
                "",
                "<template>",
                "  <h1>{{ title }}</h1>",
                "  <li v-for=\"(item, i) in items\" :key=\"item.id\" @click.prevent=\"select(item)\" #default=\"{ row }\">",
                "    {{ item.name }} &amp; more",
                "  </li>",
                "  <p v-if=\"ok\" v-else-if=\"b\" v-else class=\"a\" style=\"color: red; margin: 0\">x</p>",
                "  <comp v-bind:[key]=\"v\" @update:model-value=\"x = \$event\" :a=b v-model.trim='text' />",
                "</template>",
                "",
                "<style lang=\"scss\" scoped>",
                ".a { \$c: red; color: \$c; }",
                "</style>",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|script» «Attribute|setup» «Attribute|lang»«Punctuation|=»«String|\"ts\"»«Punctuation|>»",
                "«Keyword|const» «Variable|title»«Punctuation|:» «Type|string» «Operator|=» «String|\"OpenCode\"»",
                "«Punctuation|</»«Tag|script»«Punctuation|>»",
                "",
                "«Punctuation|<»«Tag|template»«Punctuation|>»",
                "  «Punctuation|<»«Tag|h1»«Punctuation|>{{» «Variable|title» «Punctuation|}}</»«Tag|h1»«Punctuation|>»",
                "  «Punctuation|<»«Tag|li» «Keyword|v-for»«Punctuation|=\"(»«Variable|item»«Punctuation|,» «Variable|i»«Punctuation|)» «Keyword|in» «Variable|items»«Punctuation|\"» «Punctuation|:»«Attribute|key»«Punctuation|=\"»«Variable|item»«Punctuation|.»«Property|id»«Punctuation|\"» «Punctuation|@»«Attribute|click»«Punctuation|.»«Attribute|prevent»«Punctuation|=\"»«Function|select»«Punctuation|(»«Variable|item»«Punctuation|)\"» «Punctuation|#»«Attribute|default»«Punctuation|=\"{» «Variable|row» «Punctuation|}\">»",
                "    «Punctuation|{{» «Variable|item»«Punctuation|.»«Property|name» «Punctuation|}}» «Constant|&amp;» more",
                "  «Punctuation|</»«Tag|li»«Punctuation|>»",
                "  «Punctuation|<»«Tag|p» «Keyword|v-if»«Punctuation|=\"»«Variable|ok»«Punctuation|\"» «Keyword|v-else-if»«Punctuation|=\"»«Variable|b»«Punctuation|\"» «Keyword|v-else» «Attribute|class»«Punctuation|=»«String|\"a\"» «Attribute|style»«Punctuation|=\"»«Property|color»«Punctuation|:» red«Punctuation|;» «Property|margin»«Punctuation|:» «Number|0»«Punctuation|\">»x«Punctuation|</»«Tag|p»«Punctuation|>»",
                "  «Punctuation|<»«Tag|comp» «Attribute|v-bind»«Punctuation|:[»«Attribute|key»«Punctuation|]=\"»«Variable|v»«Punctuation|\"» «Punctuation|@»«Attribute|update»«Punctuation|:»«Attribute|model-value»«Punctuation|=\"»«Variable|x» «Operator|=» «Variable|\$event»«Punctuation|\"» «Punctuation|:»«Attribute|a»«Punctuation|=»«Variable|b» «Attribute|v-model»«Punctuation|.»«Attribute|trim»«Punctuation|='»«Variable|text»«Punctuation|'» «Punctuation|/>»",
                "«Punctuation|</»«Tag|template»«Punctuation|>»",
                "",
                "«Punctuation|<»«Tag|style» «Attribute|lang»«Punctuation|=»«String|\"scss\"» «Attribute|scoped»«Punctuation|>»",
                "«Punctuation|.»«Attribute|a» «Punctuation|{» «Variable|\$c»«Punctuation|:» red«Punctuation|;» «Property|color»«Punctuation|:» «Variable|\$c»«Punctuation|;» «Punctuation|}»",
                "«Punctuation|</»«Tag|style»«Punctuation|>»",
            ),
        )
    }

    @Test
    fun expressionsAndValuesContinueAcrossLines() {
        assertGolden(
            "vue",
            goldenLines(
                "<template>",
                "  <div :class=\"{",
                "    active: isActive,",
                "  }\" @click=\"go(",
                "    1)\">",
                "    {{ items",
                "      .map(x => x * 2) }}",
                "  </div>",
                "  <p title=\"multi",
                "line &amp; text\">a {b} c {{ open",
                "  still }} after",
                "</template>",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|template»«Punctuation|>»",
                "  «Punctuation|<»«Tag|div» «Punctuation|:»«Attribute|class»«Punctuation|=\"{»",
                "    «Property|active»«Punctuation|:» «Variable|isActive»«Punctuation|,»",
                "  «Punctuation|}\"» «Punctuation|@»«Attribute|click»«Punctuation|=\"»«Function|go»«Punctuation|(»",
                "    «Number|1»«Punctuation|)\">»",
                "    «Punctuation|{{» «Variable|items»",
                "      «Punctuation|.»«Function|map»«Punctuation|(»«Variable|x» «Operator|=>» «Variable|x» «Operator|*» «Number|2»«Punctuation|)» «Punctuation|}}»",
                "  «Punctuation|</»«Tag|div»«Punctuation|>»",
                "  «Punctuation|<»«Tag|p» «Attribute|title»«Punctuation|=»«String|\"multi»",
                "«String|line »«Constant|&amp;»«String| text\"»«Punctuation|>»a {b} c «Punctuation|{{» «Variable|open»",
                "  «Variable|still» «Punctuation|}}» after",
                "«Punctuation|</»«Tag|template»«Punctuation|>»",
            ),
        )
    }

    @Test
    fun scriptStyleAndUnterminatedPieces() {
        assertGolden(
            "vue",
            goldenLines(
                "<script>",
                "export default {",
                "  data() { return { a: `x",
                "y` } }",
                "}",
                "</script>",
                "<style>",
                ".a { color: red; /* c",
                "*/ }",
                "</style>",
                "<!-- {{ not }} -->",
                "<div v-if=\"ok",
                "  && more\">{{ a }}</div>",
                "<div v-show=\"open",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|script»«Punctuation|>»",
                "«Keyword|export» «Keyword|default» «Punctuation|{»",
                "  «Function|data»«Punctuation|()» «Punctuation|{» «Keyword|return» «Punctuation|{» «Property|a»«Punctuation|:» «String|`x»",
                "«String|y`» «Punctuation|}» «Punctuation|}»",
                "«Punctuation|}»",
                "«Punctuation|</»«Tag|script»«Punctuation|>»",
                "«Punctuation|<»«Tag|style»«Punctuation|>»",
                "«Punctuation|.»«Attribute|a» «Punctuation|{» «Property|color»«Punctuation|:» red«Punctuation|;» «Comment|/* c»",
                "«Comment|*/» «Punctuation|}»",
                "«Punctuation|</»«Tag|style»«Punctuation|>»",
                "«Comment|<!-- {{ not }} -->»",
                "«Punctuation|<»«Tag|div» «Keyword|v-if»«Punctuation|=\"»«Variable|ok»",
                "  «Operator|&&» «Variable|more»«Punctuation|\">{{» «Variable|a» «Punctuation|}}</»«Tag|div»«Punctuation|>»",
                "«Punctuation|<»«Tag|div» «Attribute|v-show»«Punctuation|=\"»«Variable|open»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "vue",
            goldenLines(
                "<p title=\"😀\" :t=\"'😀'\">{{ a }} \uD83D x</p>\r",
                "<script lang=\"ts\">\r",
                "let a = `t\r",
                "\${b}`;\r",
                "</script>\r",
                "<p>{{ \"\uD83D\" }}</p>",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|p» «Attribute|title»«Punctuation|=»«String|\"😀\"» «Punctuation|:»«Attribute|t»«Punctuation|=\"»«String|'😀'»«Punctuation|\">{{» «Variable|a» «Punctuation|}}» ⟨D83D⟩ x«Punctuation|</»«Tag|p»«Punctuation|>»␍",
                "«Punctuation|<»«Tag|script» «Attribute|lang»«Punctuation|=»«String|\"ts\"»«Punctuation|>»␍",
                "«Keyword|let» «Variable|a» «Operator|=» «String|`t»␍",
                "«Punctuation|\${»«Variable|b»«Punctuation|}»«String|`»«Punctuation|;»␍",
                "«Punctuation|</»«Tag|script»«Punctuation|>»␍",
                "«Punctuation|<»«Tag|p»«Punctuation|>{{» «String|\"⟨D83D⟩\"» «Punctuation|}}</»«Tag|p»«Punctuation|>»",
            ),
        )
    }

    @Test
    fun aLineCutAtTheCapContinuesFromWhereItWasCut() {
        assertGolden(
            "vue",
            goldenLines(
                "<p :a=\"x + y + z\">{{ long }}</p>",
                "+ w\">{{ b }}",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|p» «Punctuation|:»«Attribute|a»«Punctuation|=\"»«Variable|x» «Operator|+» «Variable|y» + z\">{{ long }}</p>",
                "«Operator|+» «Variable|w»«Punctuation|\">{{» «Variable|b» «Punctuation|}}»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
