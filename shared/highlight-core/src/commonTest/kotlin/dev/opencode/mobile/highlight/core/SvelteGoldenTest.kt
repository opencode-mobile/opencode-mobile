package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Svelte fixtures: the contract for [SvelteLexer]. One escaped string per line keeps `$`, CRLF, and lone surrogates exact. */
class SvelteGoldenTest {
    @Test
    fun blocksDirectivesAndComponents() {
        assertGolden(
            "svelte",
            goldenLines(
                "<script lang=\"ts\">",
                "  let count: number = 0",
                "</script>",
                "",
                "<p>Sessions: {count}</p>",
                "{#if count > 1}",
                "  <Button on:click|once={() => { count += 1 }} bind:value={name} class:active={on} {...rest} {title}>go</Button>",
                "{:else if count === 1}",
                "  <p title=\"a {b} c\">x</p>",
                "{:else}",
                "  <p>none</p>",
                "{/if}",
                "{#each items as item, i (item.id)}{item}{/each}",
                "{#await promise then value}{value}{:catch error}{error.message}{/await}",
                "{@html raw}",
                "<svelte:head><title>x</title></svelte:head>",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|script» «Attribute|lang»«Punctuation|=»«String|\"ts\"»«Punctuation|>»",
                "  «Keyword|let» «Variable|count»«Punctuation|:» «Type|number» «Operator|=» «Number|0»",
                "«Punctuation|</»«Tag|script»«Punctuation|>»",
                "",
                "«Punctuation|<»«Tag|p»«Punctuation|>»Sessions: «Punctuation|{»«Variable|count»«Punctuation|}</»«Tag|p»«Punctuation|>»",
                "«Punctuation|{#»«Keyword|if» «Variable|count» «Operator|>» «Number|1»«Punctuation|}»",
                "  «Punctuation|<»«Type|Button» «Keyword|on»«Punctuation|:»«Type|click»«Punctuation||»«Function|once»«Punctuation|={()» «Operator|=>» «Punctuation|{» «Variable|count» «Operator|+=» «Number|1» «Punctuation|}}» «Keyword|bind»«Punctuation|:»«Variable|value»«Punctuation|={»«Variable|name»«Punctuation|}» «Attribute|class»«Punctuation|:»«Variable|active»«Punctuation|={»«Variable|on»«Punctuation|}» «Punctuation|{»«Operator|...»«Variable|rest»«Punctuation|}» «Punctuation|{»«Variable|title»«Punctuation|}>»go«Punctuation|</»«Type|Button»«Punctuation|>»",
                "«Punctuation|{:»«Keyword|else» «Keyword|if» «Variable|count» «Operator|===» «Number|1»«Punctuation|}»",
                "  «Punctuation|<»«Tag|p» «Attribute|title»«Punctuation|=»«String|\"a »«Punctuation|{»«Variable|b»«Punctuation|}»«String| c\"»«Punctuation|>»x«Punctuation|</»«Tag|p»«Punctuation|>»",
                "«Punctuation|{:»«Keyword|else»«Punctuation|}»",
                "  «Punctuation|<»«Tag|p»«Punctuation|>»none«Punctuation|</»«Tag|p»«Punctuation|>»",
                "«Punctuation|{/»«Keyword|if»«Punctuation|}»",
                "«Punctuation|{#»«Keyword|each» «Variable|items» «Keyword|as» «Variable|item»«Punctuation|,» «Variable|i» «Punctuation|(»«Variable|item»«Punctuation|.»«Property|id»«Punctuation|)}{»«Variable|item»«Punctuation|}{/»«Keyword|each»«Punctuation|}»",
                "«Punctuation|{#»«Keyword|await» «Variable|promise» «Keyword|then» «Variable|value»«Punctuation|}{»«Variable|value»«Punctuation|}{:»«Keyword|catch» «Variable|error»«Punctuation|}{»«Variable|error»«Punctuation|.»«Property|message»«Punctuation|}{/»«Keyword|await»«Punctuation|}»",
                "«Punctuation|{@»«Keyword|html» «Variable|raw»«Punctuation|}»",
                "«Punctuation|<»«Tag|svelte»«Punctuation|:»«Tag|head»«Punctuation|><»«Tag|title»«Punctuation|>»x«Punctuation|</»«Tag|title»«Punctuation|></»«Tag|svelte»«Punctuation|:»«Tag|head»«Punctuation|>»",
            ),
        )
    }

    @Test
    fun expressionsContinueAcrossLines() {
        assertGolden(
            "svelte",
            goldenLines(
                "{#if items.length > 0 &&",
                "  items[0].ok}",
                "  <ul>{#each items as item}<li on:click={() => {",
                "    select(item)",
                "  }}>{item.name}</li>{/each}</ul>",
                "{/if}",
                "<p class=\"a {b",
                "c} d\">{'}'} {\"{\"} {`a}`} {p.then(x)}</p>",
                "{unclosed",
                "  <b>still</b>",
            ),
            goldenLines(
                "«Punctuation|{#»«Keyword|if» «Variable|items»«Punctuation|.»«Property|length» «Operator|>» «Number|0» «Operator|&&»",
                "  «Variable|items»«Punctuation|[»«Number|0»«Punctuation|].»«Property|ok»«Punctuation|}»",
                "  «Punctuation|<»«Tag|ul»«Punctuation|>{#»«Keyword|each» «Variable|items» «Keyword|as» «Variable|item»«Punctuation|}<»«Tag|li» «Keyword|on»«Punctuation|:»«Type|click»«Punctuation|={()» «Operator|=>» «Punctuation|{»",
                "    «Function|select»«Punctuation|(»«Variable|item»«Punctuation|)»",
                "  «Punctuation|}}>{»«Variable|item»«Punctuation|.»«Property|name»«Punctuation|}</»«Tag|li»«Punctuation|>{/»«Keyword|each»«Punctuation|}</»«Tag|ul»«Punctuation|>»",
                "«Punctuation|{/»«Keyword|if»«Punctuation|}»",
                "«Punctuation|<»«Tag|p» «Attribute|class»«Punctuation|=»«String|\"a »«Punctuation|{»«Variable|b»",
                "«Variable|c»«Punctuation|}»«String| d\"»«Punctuation|>{»«String|'}'»«Punctuation|}» «Punctuation|{»«String|\"{\"»«Punctuation|}» «Punctuation|{»«String|`a}`»«Punctuation|}» «Punctuation|{»«Variable|p»«Punctuation|.»«Function|then»«Punctuation|(»«Variable|x»«Punctuation|)}</»«Tag|p»«Punctuation|>»",
                "«Punctuation|{»«Variable|unclosed»",
                "  «Punctuation|<»«Tag|b»«Punctuation|>»still«Punctuation|</»«Tag|b»«Punctuation|>»",
            ),
        )
    }

    @Test
    fun scriptStyleAndMoreDirectives() {
        assertGolden(
            "svelte",
            goldenLines(
                "<script context=\"module\" lang=\"ts\">",
                "  export const prerender: boolean = true;",
                "</script>",
                "<style>",
                "  p :global(.x) { margin: 0 }",
                "</style>",
                "<input bind:value={name} on:input|preventDefault={handle} use:tooltip={opts} transition:fade|local let:item style:color={c} value=\"x\">",
                "{@const area = w * h}",
                "{@debug a, b}",
                "{#snippet row(x)}<td>{x}</td>{/snippet}",
                "<!-- a {not} {{ expr }} -->",
                "<script>",
                "let open = `a",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|script» «Attribute|context»«Punctuation|=»«String|\"module\"» «Attribute|lang»«Punctuation|=»«String|\"ts\"»«Punctuation|>»",
                "  «Keyword|export» «Keyword|const» «Variable|prerender»«Punctuation|:» «Type|boolean» «Operator|=» «Constant|true»«Punctuation|;»",
                "«Punctuation|</»«Tag|script»«Punctuation|>»",
                "«Punctuation|<»«Tag|style»«Punctuation|>»",
                "  «Tag|p» «Punctuation|:»«Attribute|global»«Punctuation|(.»«Attribute|x»«Punctuation|)» «Punctuation|{» «Property|margin»«Punctuation|:» «Number|0» «Punctuation|}»",
                "«Punctuation|</»«Tag|style»«Punctuation|>»",
                "«Punctuation|<»«Tag|input» «Keyword|bind»«Punctuation|:»«Variable|value»«Punctuation|={»«Variable|name»«Punctuation|}» «Keyword|on»«Punctuation|:»«Type|input»«Punctuation||»«Function|preventDefault»«Punctuation|={»«Variable|handle»«Punctuation|}» «Keyword|use»«Punctuation|:»«Function|tooltip»«Punctuation|={»«Variable|opts»«Punctuation|}» «Keyword|transition»«Punctuation|:»«Function|fade»«Punctuation||»«Function|local» «Keyword|let»«Punctuation|:»«Variable|item» «Attribute|style»«Punctuation|:»«Variable|color»«Punctuation|={»«Variable|c»«Punctuation|}» «Attribute|value»«Punctuation|=»«String|\"x\"»«Punctuation|>»",
                "«Punctuation|{@»«Keyword|const» «Variable|area» «Operator|=» «Variable|w» «Operator|*» «Variable|h»«Punctuation|}»",
                "«Punctuation|{@»«Keyword|debug» «Variable|a»«Punctuation|,» «Variable|b»«Punctuation|}»",
                "«Punctuation|{#»«Keyword|snippet» «Function|row»«Punctuation|(»«Variable|x»«Punctuation|)}<»«Tag|td»«Punctuation|>{»«Variable|x»«Punctuation|}</»«Tag|td»«Punctuation|>{/»«Keyword|snippet»«Punctuation|}»",
                "«Comment|<!-- a {not} {{ expr }} -->»",
                "«Punctuation|<»«Tag|script»«Punctuation|>»",
                "«Keyword|let» «Variable|open» «Operator|=» «String|`a»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "svelte",
            goldenLines(
                "<p title=\"😀 {a}\">{'😀'} \uD83D</p>\r",
                "{#if a\r",
                "  && b}\r",
                "<script>\r",
                "let c = 1\r",
                "</script>\r",
                "{\"\uD83D\"}",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|p» «Attribute|title»«Punctuation|=»«String|\"😀 »«Punctuation|{»«Variable|a»«Punctuation|}»«String|\"»«Punctuation|>{»«String|'😀'»«Punctuation|}» ⟨D83D⟩«Punctuation|</»«Tag|p»«Punctuation|>»␍",
                "«Punctuation|{#»«Keyword|if» «Variable|a»␍",
                "  «Operator|&&» «Variable|b»«Punctuation|}»␍",
                "«Punctuation|<»«Tag|script»«Punctuation|>»␍",
                "«Keyword|let» «Variable|c» «Operator|=» «Number|1»␍",
                "«Punctuation|</»«Tag|script»«Punctuation|>»␍",
                "«Punctuation|{»«String|\"⟨D83D⟩\"»«Punctuation|}»",
            ),
        )
    }

    @Test
    fun aLineCutAtTheCapContinuesFromWhereItWasCut() {
        assertGolden(
            "svelte",
            goldenLines(
                "<p>{items.map(x => x)}</p>",
                "p(f)} <b>x</b>",
            ),
            goldenLines(
                "«Punctuation|<»«Tag|p»«Punctuation|>{»«Variable|items»«Punctuation|.»«Property|ma»p(x => x)}</p>",
                "«Function|p»«Punctuation|(»«Variable|f»«Punctuation|)}» «Punctuation|<»«Tag|b»«Punctuation|>»x</b>",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
