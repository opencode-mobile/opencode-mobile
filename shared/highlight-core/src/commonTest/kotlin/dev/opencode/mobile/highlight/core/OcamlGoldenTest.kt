package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written OCaml fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class OcamlGoldenTest {
    @Test
    fun typesFunctionsAndModules() {
        assertGolden(
            "ocaml",
            goldenLines(
                "type 'a tree = Leaf | Node of 'a tree * 'a * 'a tree [@@deriving show]",
                "",
                "let rec insert x = function",
                "  | Leaf -> Node (Leaf, x, Leaf)",
                "  | Node (l, v, r) when x < v -> Node (insert x l, v, r)",
                "  | t -> t",
                "",
                "let count = 42",
                "module M : sig val f : int -> int end = struct let f x = x + 1 end",
                "external length : string -> int = \"caml_ml_string_length\"",
            ),
            goldenLines(
                "«Keyword|type» «Type|'a» «Type|tree» «Operator|=» «Type|Leaf» «Operator||» «Type|Node» «Keyword|of» «Type|'a» tree «Operator|*» «Type|'a» «Operator|*» «Type|'a» tree «Attribute|[@@deriving» show«Punctuation|]»",
                "",
                "«Keyword|let» «Keyword|rec» «Function|insert» x «Operator|=» «Keyword|function»",
                "  «Operator||» «Type|Leaf» «Operator|->» «Type|Node» «Punctuation|(»«Type|Leaf»«Punctuation|,» x«Punctuation|,» «Type|Leaf»«Punctuation|)»",
                "  «Operator||» «Type|Node» «Punctuation|(»l«Punctuation|,» v«Punctuation|,» r«Punctuation|)» «Keyword|when» x «Operator|<» v «Operator|->» «Type|Node» «Punctuation|(»insert x l«Punctuation|,» v«Punctuation|,» r«Punctuation|)»",
                "  «Operator||» t «Operator|->» t",
                "",
                "«Keyword|let» «Variable|count» «Operator|=» «Number|42»",
                "«Keyword|module» «Type|M» «Operator|:» «Keyword|sig» «Keyword|val» «Function|f» «Operator|:» «Type|int» «Operator|->» «Type|int» «Keyword|end» «Operator|=» «Keyword|struct» «Keyword|let» «Function|f» x «Operator|=» x «Operator|+» «Number|1» «Keyword|end»",
                "«Keyword|external» «Function|length» «Operator|:» «Type|string» «Operator|->» «Type|int» «Operator|=» «String|\"caml_ml_string_length\"»",
            ),
        )
    }

    @Test
    fun labelsVariantsAndFields() {
        assertGolden(
            "ocaml",
            goldenLines(
                "let greet ~name ?(punct = \"!\") () =",
                "  Printf.printf \"Hello, %s%s\\n\" name punct;",
                "  ignore (`Red, List.map (fun x -> x mod 2) [1; 2], r.field, ~f:g, x lsl 2)",
                "let f ~x:y ?z = y",
            ),
            goldenLines(
                "«Keyword|let» «Function|greet» «Property|~name» «Operator|?»«Punctuation|(»punct «Operator|=» «String|\"!\"»«Punctuation|)» «Punctuation|()» «Operator|=»",
                "  «Type|Printf»«Punctuation|.»«Function|printf» «String|\"Hello, %s%s»«Constant|\\n»«String|\"» name punct«Punctuation|;»",
                "  ignore «Punctuation|(»«Constant|`Red»«Punctuation|,» «Type|List»«Punctuation|.»«Function|map» «Punctuation|(»«Keyword|fun» x «Operator|->» x «Operator|mod» «Number|2»«Punctuation|)» «Punctuation|[»«Number|1»«Punctuation|;» «Number|2»«Punctuation|],» r«Punctuation|.»«Property|field»«Punctuation|,» «Property|~f:»g«Punctuation|,» x «Operator|lsl» «Number|2»«Punctuation|)»",
                "«Keyword|let» «Function|f» «Property|~x:»y «Property|?z» «Operator|=» y",
            ),
        )
    }

    @Test
    fun stringsCharactersAndTypeVariables() {
        assertGolden(
            "ocaml",
            goldenLines(
                "let s = \"tab\\t quote\\\" dec\\065 hex\\x41 \\u{1F600}\"",
                "let c = 'x' and d = '\\n' and e = '\\'' and q = '\"'",
                "let id : 'a -> 'a = fun x' -> x'",
                "let t = {|raw \"string\" \\n|} ^ {id|has |} inside|id}",
            ),
            goldenLines(
                "«Keyword|let» «Variable|s» «Operator|=» «String|\"tab»«Constant|\\t»«String| quote»«Constant|\\\"»«String| dec»«Constant|\\065»«String| hex»«Constant|\\x41»«String| »«Constant|\\u{1F600}»«String|\"»",
                "«Keyword|let» «Variable|c» «Operator|=» «String|'x'» «Keyword|and» «Variable|d» «Operator|=» «String|'\\n'» «Keyword|and» «Variable|e» «Operator|=» «String|'\\''» «Keyword|and» «Variable|q» «Operator|=» «String|'\"'»",
                "«Keyword|let» «Variable|id» «Operator|:» «Type|'a» «Operator|->» «Type|'a» «Operator|=» «Keyword|fun» x' «Operator|->» x'",
                "«Keyword|let» «Variable|t» «Operator|=» «String|{|raw \"string\" \\n|}» «Operator|^» «String|{id|has |} inside|id}»",
            ),
        )
    }

    @Test
    fun quotedStringsAndCommentsAcrossLines() {
        assertGolden(
            "ocaml",
            goldenLines(
                "let q = {tag|first",
                "second |other} line",
                "|tag} ^ \"multi",
                "line string\"",
                "(* outer (* inner *)",
                "   still outer *) let x = 1",
                "(* a (* b (* c (* d *) *) *) *) let y = ( * ) 2 3",
                "let z = (*) a comment, as OCaml reads it *) 1",
            ),
            goldenLines(
                "«Keyword|let» «Variable|q» «Operator|=» «String|{tag|first»",
                "«String|second |other} line»",
                "«String||tag}» «Operator|^» «String|\"multi»",
                "«String|line string\"»",
                "«Comment|(* outer (* inner *)»",
                "«Comment|   still outer *)» «Keyword|let» «Variable|x» «Operator|=» «Number|1»",
                "«Comment|(* a (* b (* c (* d *) *) *) *)» «Keyword|let» «Variable|y» «Operator|=» «Punctuation|(» «Operator|*» «Punctuation|)» «Number|2» «Number|3»",
                "«Keyword|let» «Variable|z» «Operator|=» «Comment|(*) a comment, as OCaml reads it *)» «Number|1»",
            ),
        )
    }

    @Test
    fun numbers() {
        assertGolden(
            "ocaml",
            "let n = 42 + 0x1F + 0o17 + 0b101 + 1_000 + 3.14 + 1. + 6.02e23 + 1e-3 + 10l + 10L + 10n",
            "«Keyword|let» «Variable|n» «Operator|=» «Number|42» «Operator|+» «Number|0x1F» «Operator|+» «Number|0o17» «Operator|+» «Number|0b101» «Operator|+» «Number|1_000» «Operator|+» «Number|3.14» «Operator|+» «Number|1.» «Operator|+» «Number|6.02e23» «Operator|+» «Number|1e-3» «Operator|+» «Number|10l» «Operator|+» «Number|10L» «Operator|+» «Number|10n»",
        )
    }

    @Test
    fun unterminatedStringRunsToTheEnd() {
        assertGolden(
            "ocaml",
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
            "ocaml",
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
    fun unterminatedQuotedStringRunsToTheEnd() {
        assertGolden(
            "ocaml",
            goldenLines(
                "let q = {x|open |y} |x",
                "still",
            ),
            goldenLines(
                "«Keyword|let» «Variable|q» «Operator|=» «String|{x|open |y} |x»",
                "«String|still»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "ocaml",
            goldenLines(
                "let s = \"😀\" (* 😀 *)\r",
                "let c = '\uD83D' in\r",
                "let é = 'é'\r",
                "",
            ),
            goldenLines(
                "«Keyword|let» «Variable|s» «Operator|=» «String|\"😀\"» «Comment|(* 😀 *)»␍",
                "«Keyword|let» «Variable|c» «Operator|=» «String|'⟨D83D⟩'» «Keyword|in»␍",
                "«Keyword|let» «Variable|é» «Operator|=» «String|'é'»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "ocaml",
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
