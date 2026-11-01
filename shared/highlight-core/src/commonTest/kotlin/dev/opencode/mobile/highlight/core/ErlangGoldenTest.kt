package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Erlang fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class ErlangGoldenTest {
    @Test
    fun attributesAndFunctions() {
        assertGolden(
            "erlang",
            goldenLines(
                "-module(sessions).",
                "-behaviour(gen_server).",
                "-export([label/1, start_link/0]).",
                "-define(MAX, 10).",
                "-record(state, {count = 0 :: integer(), name}).",
                "-spec label(integer()) -> string().",
                "",
                "%% Formats a label.",
                "label(Count) when Count > ?MAX ->",
                "    io_lib:format(\"sessions: ~p~n ~-10.3s ~~\", [Count]);",
                "label(_) ->",
                "    ok.",
            ),
            goldenLines(
                "«Punctuation|-»«Keyword|module»«Punctuation|(»«Type|sessions»«Punctuation|).»",
                "«Punctuation|-»«Keyword|behaviour»«Punctuation|(»«Type|gen_server»«Punctuation|).»",
                "«Punctuation|-»«Keyword|export»«Punctuation|([»«Function|label»«Operator|/»«Number|1»«Punctuation|,» «Function|start_link»«Operator|/»«Number|0»«Punctuation|]).»",
                "«Punctuation|-»«Keyword|define»«Punctuation|(»«Constant|MAX»«Punctuation|,» «Number|10»«Punctuation|).»",
                "«Punctuation|-»«Keyword|record»«Punctuation|(»«Type|state»«Punctuation|,» «Punctuation|{»«Constant|count» «Operator|=» «Number|0» «Operator|::» «Function|integer»«Punctuation|(),» «Constant|name»«Punctuation|}).»",
                "«Punctuation|-»«Keyword|spec» «Function|label»«Punctuation|(»«Function|integer»«Punctuation|())» «Operator|->» «Function|string»«Punctuation|().»",
                "",
                "«Comment|%% Formats a label.»",
                "«Function|label»«Punctuation|(»«Variable|Count»«Punctuation|)» «Keyword|when» «Variable|Count» «Operator|>» «Constant|?MAX» «Operator|->»",
                "    «Type|io_lib»«Punctuation|:»«Function|format»«Punctuation|(»«String|\"sessions: »«Constant|~p~n»«String| »«Constant|~-10.3s»«String| »«Constant|~~»«String|\"»«Punctuation|,» «Punctuation|[»«Variable|Count»«Punctuation|]);»",
                "«Function|label»«Punctuation|(»«Variable|_»«Punctuation|)» «Operator|->»",
                "    «Constant|ok»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun recordsBinariesAndFuns() {
        assertGolden(
            "erlang",
            goldenLines(
                "init([]) ->",
                "    S = #state{count = 1},",
                "    N = S#state.count,",
                "    M = #{key => 1, other := 2},",
                "    <<A:8, Rest/binary>> = <<\"bin\">>,",
                "    F = fun label/1, G = fun lists:map/2,",
                "    C = \$a + \$\\n + \$\\x{41} + 16#FF + 2#101 + 2.5e-3,",
                "    'quoted atom', 'Q\\'s',",
                "    receive {msg, X} -> X after 100 -> timeout end,",
                "    case X of {ok, V} when V =:= 1 -> V; _ -> error end.",
            ),
            goldenLines(
                "«Function|init»«Punctuation|([])» «Operator|->»",
                "    «Variable|S» «Operator|=» «Punctuation|#»«Type|state»«Punctuation|{»«Constant|count» «Operator|=» «Number|1»«Punctuation|},»",
                "    «Variable|N» «Operator|=» «Variable|S»«Punctuation|#»«Type|state»«Punctuation|.»«Property|count»«Punctuation|,»",
                "    «Variable|M» «Operator|=» «Punctuation|#{»«Constant|key» «Operator|=>» «Number|1»«Punctuation|,» «Constant|other» «Operator|:=» «Number|2»«Punctuation|},»",
                "    «Punctuation|<<»«Variable|A»«Punctuation|:»«Number|8»«Punctuation|,» «Variable|Rest»«Operator|/»«Constant|binary»«Punctuation|>>» «Operator|=» «Punctuation|<<»«String|\"bin\"»«Punctuation|>>,»",
                "    «Variable|F» «Operator|=» «Keyword|fun» «Function|label»«Operator|/»«Number|1»«Punctuation|,» «Variable|G» «Operator|=» «Keyword|fun» «Type|lists»«Punctuation|:»«Function|map»«Operator|/»«Number|2»«Punctuation|,»",
                "    «Variable|C» «Operator|=» «String|\$a» «Operator|+» «String|\$\\n» «Operator|+» «String|\$\\x{41}» «Operator|+» «Number|16#FF» «Operator|+» «Number|2#101» «Operator|+» «Number|2.5e-3»«Punctuation|,»",
                "    «Constant|'quoted atom'»«Punctuation|,» «Constant|'Q\\'s'»«Punctuation|,»",
                "    «Keyword|receive» «Punctuation|{»«Constant|msg»«Punctuation|,» «Variable|X»«Punctuation|}» «Operator|->» «Variable|X» «Keyword|after» «Number|100» «Operator|->» «Constant|timeout» «Keyword|end»«Punctuation|,»",
                "    «Keyword|case» «Variable|X» «Keyword|of» «Punctuation|{»«Constant|ok»«Punctuation|,» «Variable|V»«Punctuation|}» «Keyword|when» «Variable|V» «Operator|=:=» «Number|1» «Operator|->» «Variable|V»«Punctuation|;» «Variable|_» «Operator|->» «Constant|error» «Keyword|end»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun tripleQuotedAndUnterminated() {
        assertGolden(
            "erlang",
            goldenLines(
                "T = \"\"\"",
                "    triple \"inner\" ~p",
                "    \"\"\",",
                "S = \"multi",
                "line ~s",
                "end\",",
                "U = \"never closed",
                "V = 'atom ends with its line",
                "W = 1.",
            ),
            goldenLines(
                "«Variable|T» «Operator|=» «String|\"\"\"»",
                "«String|    triple \"inner\" ~p»",
                "«String|    \"\"\"»«Punctuation|,»",
                "«Variable|S» «Operator|=» «String|\"multi»",
                "«String|line »«Constant|~s»",
                "«String|end\"»«Punctuation|,»",
                "«Variable|U» «Operator|=» «String|\"never closed»",
                "«String|V = 'atom ends with its line»",
                "«String|W = 1.»",
            ),
        )
    }

    @Test
    fun theLineCapCutsInsideAString() {
        assertGolden(
            "erlang",
            goldenLines(
                "S = \"abcdefghij\",",
                "T = 1\",",
                "U = 2.",
            ),
            goldenLines(
                "«Variable|S» «Operator|=» «String|\"abcde»fghij\",",
                "«String|T = 1\"»«Punctuation|,»",
                "«Variable|U» «Operator|=» «Number|2»«Punctuation|.»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "erlang",
            "S = \"😀 ~p\",\r\n% \uD83D\r\n'a\uDE00'.\r\n",
            goldenLines(
                "«Variable|S» «Operator|=» «String|\"😀 »«Constant|~p»«String|\"»«Punctuation|,»␍",
                "«Comment|% ⟨D83D⟩»␍",
                "«Constant|'a⟨DE00⟩'»«Punctuation|.»␍",
                "",
            ),
        )
    }
}
