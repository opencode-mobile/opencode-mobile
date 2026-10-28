package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Lua fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class LuaGoldenTest {
    @Test
    fun functionsCallsAndStrings() {
        assertGolden(
            "lua",
            goldenLines(
                "#!/usr/bin/env lua",
                "local function label(count, ...)",
                "  -- format a label",
                "  return \"sessions: \" .. count .. '\\n\\x41\\u{48}\\065'",
                "end",
                "function M.util:render(t) return self.x, #t, t[1] ~= nil end",
                "print(\"x\"); require \"mod\"; setmetatable({}, mt)",
            ),
            goldenLines(
                "«Comment|#!/usr/bin/env lua»",
                "«Keyword|local» «Keyword|function» «Function|label»«Punctuation|(»«Variable|count»«Punctuation|,» «Operator|...»«Punctuation|)»",
                "  «Comment|-- format a label»",
                "  «Keyword|return» «String|\"sessions: \"» «Operator|..» count «Operator|..» «String|'»«Constant|\\n\\x41\\u{48}\\065»«String|'»",
                "«Keyword|end»",
                "«Keyword|function» M«Punctuation|.»util«Punctuation|:»«Function|render»«Punctuation|(»«Variable|t»«Punctuation|)» «Keyword|return» «Variable|self»«Punctuation|.»«Property|x»«Punctuation|,» «Operator|#»t«Punctuation|,» t«Punctuation|[»«Number|1»«Punctuation|]» «Operator|~=» «Constant|nil» «Keyword|end»",
                "«Function|print»«Punctuation|(»«String|\"x\"»«Punctuation|);» «Function|require» «String|\"mod\"»«Punctuation|;» «Function|setmetatable»«Punctuation|({},» mt«Punctuation|)»",
            ),
        )
    }

    @Test
    fun longStringsAndCommentsSpanLines() {
        assertGolden(
            "lua",
            goldenLines(
                "local s = [==[",
                "long ]] still",
                "]==] .. 0x1p4 + 1.5e3 // 2",
                "--[[ block",
                "comment ]] x = obj.field",
                "x = --[==[ a ]] b",
                "still ]=] ]==] y",
            ),
            goldenLines(
                "«Keyword|local» s «Operator|=» «String|[==[»",
                "«String|long ]] still»",
                "«String|]==]» «Operator|..» «Number|0x1p4» «Operator|+» «Number|1.5e3» «Operator|//» «Number|2»",
                "«Comment|--[[ block»",
                "«Comment|comment ]]» x «Operator|=» obj«Punctuation|.»«Property|field»",
                "x «Operator|=» «Comment|--[==[ a ]] b»",
                "«Comment|still ]=] ]==]» y",
            ),
        )
    }

    @Test
    fun escapesContinueShortStrings() {
        assertGolden(
            "lua",
            goldenLines(
                "local u = \"continued \\z",
                "   more\" .. 'next \\",
                "line'",
                "s = 'unterminated",
                "z = 1",
                "x = [[unterminated",
                "y = 2",
            ),
            goldenLines(
                "«Keyword|local» u «Operator|=» «String|\"continued »«Constant|\\z»",
                "«String|   more\"» «Operator|..» «String|'next »«Constant|\\»",
                "«String|line'»",
                "s «Operator|=» «String|'unterminated»",
                "z «Operator|=» «Number|1»",
                "x «Operator|=» «String|[[unterminated»",
                "«String|y = 2»",
            ),
        )
    }

    @Test
    fun operatorsMethodsAndSugarCalls() {
        assertGolden(
            "lua",
            goldenLines(
                "local a <const> = obj:method \"s\" .. t.f {1} .. f [[x]]",
                "for i = 1, #t do t[i] = i * 2 end",
                "while x >= 0x10 and not y do x = x - 1 end",
                "local x = 3 // 2 ~ 1 << 4",
                "goto done ::done::",
            ),
            goldenLines(
                "«Keyword|local» a «Operator|<»const«Operator|>» «Operator|=» obj«Punctuation|:»«Function|method» «String|\"s\"» «Operator|..» t«Punctuation|.»«Function|f» «Punctuation|{»«Number|1»«Punctuation|}» «Operator|..» «Function|f» «String|[[x]]»",
                "«Keyword|for» i «Operator|=» «Number|1»«Punctuation|,» «Operator|#»t «Keyword|do» t«Punctuation|[»i«Punctuation|]» «Operator|=» i «Operator|*» «Number|2» «Keyword|end»",
                "«Keyword|while» x «Operator|>=» «Number|0x10» «Keyword|and» «Keyword|not» y «Keyword|do» x «Operator|=» x «Operator|-» «Number|1» «Keyword|end»",
                "«Keyword|local» x «Operator|=» «Number|3» «Operator|//» «Number|2» «Operator|~» «Number|1» «Operator|<<» «Number|4»",
                "«Keyword|goto» done «Punctuation|::»done«Punctuation|::»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "lua",
            "print(\"😀 \\u{1F600}\")\r\n-- \uD83D\r\nx = \uD83Dy\r\nlocal s = \"open\r\n",
            goldenLines(
                "«Function|print»«Punctuation|(»«String|\"😀 »«Constant|\\u{1F600}»«String|\"»«Punctuation|)»␍",
                "«Comment|-- ⟨D83D⟩»␍",
                "x «Operator|=» ⟨D83D⟩y␍",
                "«Keyword|local» s «Operator|=» «String|\"open»␍",
                "",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "lua",
            goldenLines(
                "s = [[abc def ghi]]",
                "print(s)",
            ),
            goldenLines(
                "s «Operator|=» «String|[[abc de»f ghi]]",
                "«String|print(s)»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
