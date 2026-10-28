package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written MATLAB fixtures: the contract for [MatlabLexer]. One escaped string per line keeps quotes, CRLF, and lone surrogates exact. */
class MatlabGoldenTest {
    @Test
    fun functionsKeywordsAndEnd() {
        assertGolden(
            "matlab",
            goldenLines(
                "function [a, b] = label(count, ~)",
                "% label a count",
                "text = sprintf(\"sessions: %d\", count);",
                "if x(end) > 1 && y ~= 2",
                "    a = c{end};",
                "elseif isempty(x)",
                "    return",
                "else",
                "    b = nargin;",
                "end",
                "for k = 1:numel(v), total = total + v(k); end",
                "end",
                "function y = square(x)",
                "y = x.^2;",
                "end",
                "function noArgs",
                "end",
            ),
            goldenLines(
                "«Keyword|function» «Punctuation|[»«Variable|a»«Punctuation|,» «Variable|b»«Punctuation|]» «Operator|=» «Function|label»«Punctuation|(»«Variable|count»«Punctuation|,» «Operator|~»«Punctuation|)»",
                "«Comment|% label a count»",
                "text «Operator|=» sprintf«Punctuation|(»«String|\"sessions: %d\"»«Punctuation|,» count«Punctuation|);»",
                "«Keyword|if» x«Punctuation|(»«Constant|end»«Punctuation|)» «Operator|>» «Number|1» «Operator|&&» y «Operator|~=» «Number|2»",
                "    a «Operator|=» c«Punctuation|{»«Constant|end»«Punctuation|};»",
                "«Keyword|elseif» isempty«Punctuation|(»x«Punctuation|)»",
                "    «Keyword|return»",
                "«Keyword|else»",
                "    b «Operator|=» «Variable|nargin»«Punctuation|;»",
                "«Keyword|end»",
                "«Keyword|for» k «Operator|=» «Number|1»«Operator|:»numel«Punctuation|(»v«Punctuation|),» total «Operator|=» total «Operator|+» v«Punctuation|(»k«Punctuation|);» «Keyword|end»",
                "«Keyword|end»",
                "«Keyword|function» «Variable|y» «Operator|=» «Function|square»«Punctuation|(»«Variable|x»«Punctuation|)»",
                "y «Operator|=» x«Operator|.^»«Number|2»«Punctuation|;»",
                "«Keyword|end»",
                "«Keyword|function» «Function|noArgs»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun stringsAndTransposes() {
        assertGolden(
            "matlab",
            goldenLines(
                "x = a' + b.' * c';",
                "s = 'it''s';",
                "d = \"say \"\"hi\"\"\";",
                "m = [a 'str' b'];",
                "n = [a' 'x'];",
                "t = x(1)' + {1}' + 2';",
                "u = 'open",
                "v = \"open",
            ),
            goldenLines(
                "x «Operator|=» a«Operator|'» «Operator|+» b«Operator|.'» «Operator|*» c«Operator|'»«Punctuation|;»",
                "s «Operator|=» «String|'it»«Constant|''»«String|s'»«Punctuation|;»",
                "d «Operator|=» «String|\"say »«Constant|\"\"»«String|hi»«Constant|\"\"»«String|\"»«Punctuation|;»",
                "m «Operator|=» «Punctuation|[»a «String|'str'» b«Operator|'»«Punctuation|];»",
                "n «Operator|=» «Punctuation|[»a«Operator|'» «String|'x'»«Punctuation|];»",
                "t «Operator|=» x«Punctuation|(»«Number|1»«Punctuation|)»«Operator|'» «Operator|+» «Punctuation|{»«Number|1»«Punctuation|}»«Operator|'» «Operator|+» «Number|2»«Operator|'»«Punctuation|;»",
                "u «Operator|=» «String|'open»",
                "v «Operator|=» «String|\"open»",
            ),
        )
    }

    @Test
    fun commentsBlockCommentsAndContinuation() {
        assertGolden(
            "matlab",
            goldenLines(
                "%% Section",
                "y = [1 2 ...  continued here",
                "  3]; z = s.field;",
                "%{",
                "block % still",
                "  %{",
                "  nested",
                "  %}",
                "%}",
                "x = 1 %{ not a block",
                "  %{ trailing",
                "%}",
                "%{",
                "unterminated",
            ),
            goldenLines(
                "«Comment|%% Section»",
                "y «Operator|=» «Punctuation|[»«Number|1» «Number|2» «Operator|...»«Comment|  continued here»",
                "  «Number|3»«Punctuation|];» z «Operator|=» s«Punctuation|.»«Property|field»«Punctuation|;»",
                "«Comment|%{»",
                "«Comment|block % still»",
                "«Comment|  %{»",
                "«Comment|  nested»",
                "«Comment|  %}»",
                "«Comment|%}»",
                "x «Operator|=» «Number|1» «Comment|%{ not a block»",
                "  «Comment|%{ trailing»",
                "«Comment|%}»",
                "«Comment|%{»",
                "«Comment|unterminated»",
            ),
        )
    }

    @Test
    fun classdefCommandsAndNumbers() {
        assertGolden(
            "matlab",
            goldenLines(
                "classdef Account < handle",
                "    properties (Access = private)",
                "        Balance = 0",
                "    end",
                "    methods",
                "        function obj = deposit(obj, amount)",
                "            obj.Balance = obj.Balance + amount;",
                "        end",
                "        function set.Balance(obj, v)",
                "        end",
                "    end",
                "end",
                "hold on",
                "format long g; clear all",
                "disp -x",
                "x = methods(obj);",
                "f = @(x) x.^2 + 3i + 0x1F + 0b101 + 1.5e-3 + .5;",
                "!ls -la",
                "v = true | false & ~pi",
            ),
            goldenLines(
                "«Keyword|classdef» Account «Operator|<» handle",
                "    «Keyword|properties» «Punctuation|(»Access «Operator|=» private«Punctuation|)»",
                "        Balance «Operator|=» «Number|0»",
                "    «Keyword|end»",
                "    «Keyword|methods»",
                "        «Keyword|function» «Variable|obj» «Operator|=» «Function|deposit»«Punctuation|(»«Variable|obj»«Punctuation|,» «Variable|amount»«Punctuation|)»",
                "            obj«Punctuation|.»«Property|Balance» «Operator|=» obj«Punctuation|.»«Property|Balance» «Operator|+» amount«Punctuation|;»",
                "        «Keyword|end»",
                "        «Keyword|function» «Function|set.Balance»«Punctuation|(»«Variable|obj»«Punctuation|,» «Variable|v»«Punctuation|)»",
                "        «Keyword|end»",
                "    «Keyword|end»",
                "«Keyword|end»",
                "«Function|hold» «String|on»",
                "«Function|format» «String|long g»«Punctuation|;» «Function|clear» «String|all»",
                "«Function|disp» «String|-x»",
                "x «Operator|=» methods«Punctuation|(»obj«Punctuation|);»",
                "f «Operator|=» «Operator|@»«Punctuation|(»x«Punctuation|)» x«Operator|.^»«Number|2» «Operator|+» «Number|3i» «Operator|+» «Number|0x1F» «Operator|+» «Number|0b101» «Operator|+» «Number|1.5e-3» «Operator|+» «Number|.5»«Punctuation|;»",
                "«String|!ls -la»",
                "v «Operator|=» «Constant|true» «Operator||» «Constant|false» «Operator|&» «Operator|~»«Constant|pi»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "matlab",
            goldenLines(
                "s = '😀';\r",
                "%{\r",
                "\uD83D\r",
                "%}\r",
                "t = x';\r",
                "",
            ),
            goldenLines(
                "s «Operator|=» «String|'😀'»«Punctuation|;»␍",
                "«Comment|%{»␍",
                "«Comment|⟨D83D⟩»␍",
                "«Comment|%}»␍",
                "t «Operator|=» x«Operator|'»«Punctuation|;»␍",
                "",
            ),
        )
    }
}
