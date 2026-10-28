package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written F# fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class FSharpGoldenTest {
    @Test
    fun bindingsMembersAndAttributes() {
        assertGolden(
            "fsharp",
            goldenLines(
                "// Line comment",
                "/// Doc comment",
                "[<EntryPoint; Obsolete(\"x\")>]",
                "let main argv =",
                "    let name = \"world\"",
                "    let g (x: 'T) : 'T list = [x]",
                "    0",
                "",
                "type Person(name: string) =",
                "    member this.Name = name",
                "    member this.Greet(other: Person) = sprintf \"%s\" other.Name",
                "    static member Create() = Person(\"x\")",
                "    override _.ToString() = name",
            ),
            goldenLines(
                "«Comment|// Line comment»",
                "«Comment|/// Doc comment»",
                "«Attribute|[<EntryPoint»«Punctuation|;» «Attribute|Obsolete»«Punctuation|(»«String|\"x\"»«Punctuation|)»«Attribute|>]»",
                "«Keyword|let» «Function|main» argv «Operator|=»",
                "    «Keyword|let» «Variable|name» «Operator|=» «String|\"world\"»",
                "    «Keyword|let» «Function|g» «Punctuation|(»x«Operator|:» «Type|'T»«Punctuation|)» «Operator|:» «Type|'T» «Type|list» «Operator|=» «Punctuation|[»x«Punctuation|]»",
                "    «Number|0»",
                "",
                "«Keyword|type» «Type|Person»«Punctuation|(»name«Operator|:» «Type|string»«Punctuation|)» «Operator|=»",
                "    «Keyword|member» this«Punctuation|.»«Property|Name» «Operator|=» name",
                "    «Keyword|member» this«Punctuation|.»«Function|Greet»«Punctuation|(»other«Operator|:» «Type|Person»«Punctuation|)» «Operator|=» sprintf «String|\"%s\"» other«Punctuation|.»«Property|Name»",
                "    «Keyword|static» «Keyword|member» «Function|Create»«Punctuation|()» «Operator|=» «Type|Person»«Punctuation|(»«String|\"x\"»«Punctuation|)»",
                "    «Keyword|override» _«Punctuation|.»«Function|ToString»«Punctuation|()» «Operator|=» name",
            ),
        )
    }

    @Test
    fun computationExpressions() {
        assertGolden(
            "fs",
            goldenLines(
                "let fetchAll urls =",
                "    async {",
                "        let! pages = urls |> List.map fetch |> Async.Parallel",
                "        use! conn = open ()",
                "        do! log \"done\"",
                "        match! check () with",
                "        | Some x -> return! other x",
                "        | None -> yield! []",
                "    }",
                "let xs = seq { 1 .. 10 }",
                "let s = seq<int>",
            ),
            goldenLines(
                "«Keyword|let» «Function|fetchAll» urls «Operator|=»",
                "    «Keyword|async» «Punctuation|{»",
                "        «Keyword|let!» «Variable|pages» «Operator|=» urls «Operator||>» «Type|List»«Punctuation|.»«Function|map» fetch «Operator||>» «Type|Async»«Punctuation|.»«Type|Parallel»",
                "        «Keyword|use!» «Variable|conn» «Operator|=» «Keyword|open» «Punctuation|()»",
                "        «Keyword|do!» log «String|\"done\"»",
                "        «Keyword|match!» check «Punctuation|()» «Keyword|with»",
                "        «Operator||» «Type|Some» x «Operator|->» «Keyword|return!» other x",
                "        «Operator||» «Type|None» «Operator|->» «Keyword|yield!» «Punctuation|[]»",
                "    «Punctuation|}»",
                "«Keyword|let» «Variable|xs» «Operator|=» «Keyword|seq» «Punctuation|{» «Number|1» «Operator|..» «Number|10» «Punctuation|}»",
                "«Keyword|let» «Variable|s» «Operator|=» «Type|seq»«Operator|<»«Type|int»«Operator|>»",
            ),
        )
    }

    @Test
    fun stringKinds() {
        assertGolden(
            "f#",
            goldenLines(
                "let a = \"tab\\t quote\\\" uni\\u0041\"",
                "let v = @\"C:\\path \"\"q\"\"\"",
                "let t = \"\"\"triple \"quoted\" text\"\"\"",
                "let b = \"bytes\"B",
                "let c = 'a' :: '\\n' :: 'x'B :: []",
                "let mult = (*) 2 3",
            ),
            goldenLines(
                "«Keyword|let» «Variable|a» «Operator|=» «String|\"tab»«Constant|\\t»«String| quote»«Constant|\\\"»«String| uni»«Constant|\\u0041»«String|\"»",
                "«Keyword|let» «Variable|v» «Operator|=» «String|@\"C:\\path »«Constant|\"\"»«String|q»«Constant|\"\"»«String|\"»",
                "«Keyword|let» «Variable|t» «Operator|=» «String|\"\"\"triple \"quoted\" text\"\"\"»",
                "«Keyword|let» «Variable|b» «Operator|=» «String|\"bytes\"B»",
                "«Keyword|let» «Variable|c» «Operator|=» «String|'a'» «Operator|::» «String|'\\n'» «Operator|::» «String|'x'B» «Operator|::» «Punctuation|[]»",
                "«Keyword|let» «Variable|mult» «Operator|=» «Operator|(*)» «Number|2» «Number|3»",
            ),
        )
    }

    @Test
    fun interpolatedStrings() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let i = \$\"Hello {name} {{literal}} {x + 1}\"",
                "let v = \$@\"path {dir}\\file\" + @\$\"other {x}\"",
                "let t = \$\"\"\"json {{ \"a\": {value} }}\"\"\"",
                "let d = \$\$\"\"\"x {y} {{z}} {{{w}}}\"\"\"",
                "let n = \$\"outer {f \$\"inner\"} end\"",
            ),
            goldenLines(
                "«Keyword|let» «Variable|i» «Operator|=» «String|\$\"Hello »«Punctuation|{»name«Punctuation|}»«String| »«Constant|{{»«String|literal»«Constant|}}»«String| »«Punctuation|{»x «Operator|+» «Number|1»«Punctuation|}»«String|\"»",
                "«Keyword|let» «Variable|v» «Operator|=» «String|\$@\"path »«Punctuation|{»dir«Punctuation|}»«String|\\file\"» «Operator|+» «String|@\$\"other »«Punctuation|{»x«Punctuation|}»«String|\"»",
                "«Keyword|let» «Variable|t» «Operator|=» «String|\$\"\"\"json »«Constant|{{»«String| \"a\": »«Punctuation|{»value«Punctuation|}»«String| »«Constant|}}»«String|\"\"\"»",
                "«Keyword|let» «Variable|d» «Operator|=» «String|\$\$\"\"\"x {y} »«Punctuation|{{»z«Punctuation|}}»«String| {»«Punctuation|{{»w«Punctuation|}}»«String|}\"\"\"»",
                "«Keyword|let» «Variable|n» «Operator|=» «String|\$\"outer »«Punctuation|{»f «String|\$\"inner\"»«Punctuation|}»«String| end\"»",
            ),
        )
    }

    @Test
    fun stringsAndHolesAcrossLines() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let o = \$\"start {",
                "  value",
                "} end\"",
                "let m = \$\"\"\"a {1 + 2} \"b\"",
                "    c {name}\"\"\"",
                "let s = \"multi",
                "line\" + @\"verbatim",
                "\"\"quoted\"\"\"",
                "(* outer (* inner *)",
                "   still outer *) let x = 1",
            ),
            goldenLines(
                "«Keyword|let» «Variable|o» «Operator|=» «String|\$\"start »«Punctuation|{»",
                "  value",
                "«Punctuation|}»«String| end\"»",
                "«Keyword|let» «Variable|m» «Operator|=» «String|\$\"\"\"a »«Punctuation|{»«Number|1» «Operator|+» «Number|2»«Punctuation|}»«String| \"b\"»",
                "«String|    c »«Punctuation|{»name«Punctuation|}»«String|\"\"\"»",
                "«Keyword|let» «Variable|s» «Operator|=» «String|\"multi»",
                "«String|line\"» «Operator|+» «String|@\"verbatim»",
                "«Constant|\"\"»«String|quoted»«Constant|\"\"»«String|\"»",
                "«Comment|(* outer (* inner *)»",
                "«Comment|   still outer *)» «Keyword|let» «Variable|x» «Operator|=» «Number|1»",
            ),
        )
    }

    @Test
    fun directivesNumbersAndOperators() {
        assertGolden(
            "fsharp",
            goldenLines(
                "#if DEBUG",
                "let debug = true",
                "#endif",
                "#r \"nuget: FSharp.Data\"",
                "let x = 1uy + 0x1F + 1.5m + 2.0f + 100L + 1_000 + 6.02e23",
                "let f = x |> g >> h <| y :: zs @ [||] <- z",
                "let arr = [| 1; 2 |]",
            ),
            goldenLines(
                "«Keyword|#if» «Type|DEBUG»",
                "«Keyword|let» «Variable|debug» «Operator|=» «Constant|true»",
                "«Keyword|#endif»",
                "«Keyword|#r» «String|\"nuget: FSharp.Data\"»",
                "«Keyword|let» «Variable|x» «Operator|=» «Number|1uy» «Operator|+» «Number|0x1F» «Operator|+» «Number|1.5m» «Operator|+» «Number|2.0f» «Operator|+» «Number|100L» «Operator|+» «Number|1_000» «Operator|+» «Number|6.02e23»",
                "«Keyword|let» «Variable|f» «Operator|=» x «Operator||>» g «Operator|>>» h «Operator|<|» y «Operator|::» zs «Operator|@» «Punctuation|[||]» «Operator|<-» z",
                "«Keyword|let» «Variable|arr» «Operator|=» «Punctuation|[|» «Number|1»«Punctuation|;» «Number|2» «Punctuation||]»",
            ),
        )
    }

    @Test
    fun unterminatedStringRunsToTheEnd() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let s = \"open",
                "let t = 1",
            ),
            goldenLines(
                "«Keyword|let» «Variable|s» «Operator|=» «String|\"open»",
                "«String|let t = 1»",
            ),
        )
    }

    @Test
    fun unterminatedCommentRunsToTheEnd() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let x = 1 (* open (* nested *)",
                "still",
            ),
            goldenLines(
                "«Keyword|let» «Variable|x» «Operator|=» «Number|1» «Comment|(* open (* nested *)»",
                "«Comment|still»",
            ),
        )
    }

    @Test
    fun unterminatedHoleAndTripleQuote() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let i = \$\"hole {x +",
                "  \"s\" (* c *) // line",
                "let u = \"\"\"open",
                "still",
            ),
            goldenLines(
                "«Keyword|let» «Variable|i» «Operator|=» «String|\$\"hole »«Punctuation|{»x «Operator|+»",
                "  «String|\"s\"» «Comment|(* c *)» «Comment|// line»",
                "«Keyword|let» «Variable|u» «Operator|=» «String|\"\"\"open»",
                "«String|still»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let s = \"😀\" // 😀\r",
                "let c = '\uD83D'\r",
                "let é = \$\"{x}é\"\r",
                "",
            ),
            goldenLines(
                "«Keyword|let» «Variable|s» «Operator|=» «String|\"😀\"» «Comment|// 😀»␍",
                "«Keyword|let» «Variable|c» «Operator|=» «String|'⟨D83D⟩'»␍",
                "«Keyword|let» «Variable|é» «Operator|=» «String|\$\"»«Punctuation|{»x«Punctuation|}»«String|é\"»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "fsharp",
            goldenLines(
                "let s = \"abcdefghij\"",
                "(* abcdefghij",
                "*) x",
            ),
            goldenLines(
                "«Keyword|let» «Variable|s» «Operator|=» «String|\"a»bcdefghij\"",
                "«String|(* abcdefg»hij",
                "«String|*) x»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
