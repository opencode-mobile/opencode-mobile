package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Prolog fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class PrologGoldenTest {
    @Test
    fun clausesAndDirectives() {
        assertGolden(
            "prolog",
            goldenLines(
                ":- module(app, [greet/1]).",
                ":- use_module(library(lists)).",
                ":- dynamic counter/1.",
                "",
                "% A comment",
                "greet(Name) :-",
                "    format(\"Hello, ~w~n\", [Name]),",
                "    atom_length(Name, Len),",
                "    Len > 0, !.",
                "",
                "main :- X is 2 + 3 * 4, X =:= 14, X mod 2 =:= 0.",
                "grammar --> [a], grammar.",
                "member_(X, [X|_]) :- true ; fail.",
                "?- greet(world).",
            ),
            goldenLines(
                "«Keyword|:-» «Keyword|module»«Punctuation|(»app«Punctuation|,» «Punctuation|[»greet«Operator|/»«Number|1»«Punctuation|]).»",
                "«Keyword|:-» «Keyword|use_module»«Punctuation|(»«Function|library»«Punctuation|(»lists«Punctuation|)).»",
                "«Keyword|:-» «Keyword|dynamic» counter«Operator|/»«Number|1»«Punctuation|.»",
                "",
                "«Comment|% A comment»",
                "«Function|greet»«Punctuation|(»«Variable|Name»«Punctuation|)» «Keyword|:-»",
                "    «Function|format»«Punctuation|(»«String|\"Hello, ~w~n\"»«Punctuation|,» «Punctuation|[»«Variable|Name»«Punctuation|]),»",
                "    «Function|atom_length»«Punctuation|(»«Variable|Name»«Punctuation|,» «Variable|Len»«Punctuation|),»",
                "    «Variable|Len» «Operator|>» «Number|0»«Punctuation|,» «Keyword|!»«Punctuation|.»",
                "",
                "«Function|main» «Keyword|:-» «Variable|X» «Keyword|is» «Number|2» «Operator|+» «Number|3» «Operator|*» «Number|4»«Punctuation|,» «Variable|X» «Operator|=:=» «Number|14»«Punctuation|,» «Variable|X» «Operator|mod» «Number|2» «Operator|=:=» «Number|0»«Punctuation|.»",
                "«Function|grammar» «Keyword|-->» «Punctuation|[»a«Punctuation|],» grammar«Punctuation|.»",
                "«Function|member_»«Punctuation|(»«Variable|X»«Punctuation|,» «Punctuation|[»«Variable|X»«Punctuation||»«Variable|_»«Punctuation|])» «Keyword|:-» «Constant|true» «Operator|;» «Constant|fail»«Punctuation|.»",
                "«Keyword|?-» «Function|greet»«Punctuation|(»world«Punctuation|).»",
            ),
        )
    }

    @Test
    fun atomsStringsAndNumbers() {
        assertGolden(
            "prolog",
            goldenLines(
                "parent(tom, 'Bob Smith').",
                "quote('it''s', \"say \\\"hi\\\"\", `codes`, 'tab\\t', 'hex\\x41\\').",
                "nums(42, 3.14, 1.0e10, 0x1F, 0o17, 0b101, 1_000, 0'a, 0' , 0''', 0'\\n).",
                "ops(X) :- X = f(Y), Y == a, \\+ b, X =.. L, _Ignored = L.",
            ),
            goldenLines(
                "«Function|parent»«Punctuation|(»tom«Punctuation|,» «String|'Bob Smith'»«Punctuation|).»",
                "«Function|quote»«Punctuation|(»«String|'it»«Constant|''»«String|s'»«Punctuation|,» «String|\"say »«Constant|\\\"»«String|hi»«Constant|\\\"»«String|\"»«Punctuation|,» «String|`codes`»«Punctuation|,» «String|'tab»«Constant|\\t»«String|'»«Punctuation|,» «String|'hex»«Constant|\\x41\\»«String|'»«Punctuation|).»",
                "«Function|nums»«Punctuation|(»«Number|42»«Punctuation|,» «Number|3.14»«Punctuation|,» «Number|1.0e10»«Punctuation|,» «Number|0x1F»«Punctuation|,» «Number|0o17»«Punctuation|,» «Number|0b101»«Punctuation|,» «Number|1_000»«Punctuation|,» «Number|0'a»«Punctuation|,» «Number|0'» «Punctuation|,» «Number|0'''»«Punctuation|,» «Number|0'\\n»«Punctuation|).»",
                "«Function|ops»«Punctuation|(»«Variable|X»«Punctuation|)» «Keyword|:-» «Variable|X» «Operator|=» «Function|f»«Punctuation|(»«Variable|Y»«Punctuation|),» «Variable|Y» «Operator|==» a«Punctuation|,» «Operator|\\+» b«Punctuation|,» «Variable|X» «Operator|=..» «Variable|L»«Punctuation|,» «Variable|_Ignored» «Operator|=» «Variable|L»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun commentsAndContinuations() {
        assertGolden(
            "prolog",
            goldenLines(
                "/* block",
                "   comment */ a.",
                "b :- X = 'line one \\",
                "line two', Y = \"open",
                "c.",
            ),
            goldenLines(
                "«Comment|/* block»",
                "«Comment|   comment */» a«Punctuation|.»",
                "«Function|b» «Keyword|:-» «Variable|X» «Operator|=» «String|'line one »«Constant|\\»",
                "«String|line two'»«Punctuation|,» «Variable|Y» «Operator|=» «String|\"open»",
                "«Function|c»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "prolog",
            goldenLines(
                "a('😀'). % 😀\r",
                "b(\"\uD83D\").\r",
                "c(é).\r",
                "",
            ),
            goldenLines(
                "«Function|a»«Punctuation|(»«String|'😀'»«Punctuation|).» «Comment|% 😀»␍",
                "«Function|b»«Punctuation|(»«String|\"⟨D83D⟩\"»«Punctuation|).»␍",
                "«Function|c»«Punctuation|(»é«Punctuation|).»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "prolog",
            goldenLines(
                "a('abcdefghij').",
                "/* abcdefghij",
                "*/ b.",
            ),
            goldenLines(
                "«Function|a»«Punctuation|(»«String|'abcdefg»hij').",
                "«Comment|/* abcdefg»hij",
                "«Comment|*/» b«Punctuation|.»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
