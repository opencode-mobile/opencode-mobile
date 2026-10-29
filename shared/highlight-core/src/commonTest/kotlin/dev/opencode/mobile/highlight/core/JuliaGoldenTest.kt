package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Julia fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class JuliaGoldenTest {
    @Test
    fun functionsTypesAndInterpolation() {
        assertGolden(
            "julia",
            goldenLines(
                "# Sessions",
                "module Sessions",
                "using JSON: parse",
                "const MAX_SIZE = 10",
                "function label(count::Int, opts::Dict{String,Any} = Dict())::String",
                "    return \"sessions: \$count of \$(MAX_SIZE - 1)\\n\"",
                "end",
                "area(r) = π * r^2",
                "function Base.show(io::IO, s::Session) end",
                "abstract type Shape end",
                "mutable struct Point{T<:Real} <: Shape",
                "    x::T",
                "end",
                "end",
            ),
            goldenLines(
                "«Comment|# Sessions»",
                "«Keyword|module» «Type|Sessions»",
                "«Keyword|using» «Constant|JSON»«Operator|:» parse",
                "«Keyword|const» «Constant|MAX_SIZE» «Operator|=» «Number|10»",
                "«Keyword|function» «Function|label»«Punctuation|(»count«Operator|::»«Type|Int»«Punctuation|,» opts«Operator|::»«Type|Dict»«Punctuation|{»«Type|String»«Punctuation|,»«Type|Any»«Punctuation|}» «Operator|=» «Function|Dict»«Punctuation|())»«Operator|::»«Type|String»",
                "    «Keyword|return» «String|\"sessions: »«Variable|\$count»«String| of »«Punctuation|\$(»«Constant|MAX_SIZE» «Operator|-» «Number|1»«Punctuation|)»«Constant|\\n»«String|\"»",
                "«Keyword|end»",
                "«Function|area»«Punctuation|(»r«Punctuation|)» «Operator|=» π «Operator|*» r«Operator|^»«Number|2»",
                "«Keyword|function» «Type|Base»«Punctuation|.»«Function|show»«Punctuation|(»io«Operator|::»«Type|IO»«Punctuation|,» s«Operator|::»«Type|Session»«Punctuation|)» «Keyword|end»",
                "«Keyword|abstract» «Keyword|type» «Type|Shape» «Keyword|end»",
                "«Keyword|mutable» «Keyword|struct» «Type|Point»«Punctuation|{»«Type|T»«Operator|<:»«Type|Real»«Punctuation|}» «Operator|<:» «Type|Shape»",
                "    x«Operator|::»«Type|T»",
                "«Keyword|end»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun stringsMacrosAndCommands() {
        assertGolden(
            "julia",
            goldenLines(
                "s = \"\"\"",
                "    Hello \$name, \$(user.id)",
                "    \"quoted\" \\t",
                "    \"\"\"",
                "r = r\"\\d+\\\"x\"im",
                "p = raw\"C:\\path\\\$x\"",
                "v = v\"1.2.3\"",
                "c = `ls -la \$dir`",
                "html\"\"\"<b>\$x</b>\"\"\" * b\"bytes\"",
            ),
            goldenLines(
                "s «Operator|=» «String|\"\"\"»",
                "«String|    Hello »«Variable|\$name»«String|, »«Punctuation|\$(»user«Punctuation|.»«Property|id»«Punctuation|)»",
                "«String|    \"quoted\" »«Constant|\\t»",
                "«String|    \"\"\"»",
                "r «Operator|=» «Function|r»«String|\"\\d+»«Constant|\\\"»«String|x\"im»",
                "p «Operator|=» «Function|raw»«String|\"C:\\path\\\$x\"»",
                "v «Operator|=» «Function|v»«String|\"1.2.3\"»",
                "c «Operator|=» «String|`ls -la »«Variable|\$dir»«String|`»",
                "«Function|html»«String|\"\"\"<b>\$x</b>\"\"\"» «Operator|*» «Function|b»«String|\"bytes\"»",
            ),
        )
    }

    @Test
    fun charactersTransposeAndSymbols() {
        assertGolden(
            "julia",
            goldenLines(
                "c = 'a' * '\\n' * '\\u00e9' * '😀'",
                "B = A' * x[1]' + (A .+ 1)'",
                "d = Dict(:key => 1, :other! => 2)",
                "r = 1:n; s = a[2:end]; t = x ? :yes : :no",
                "q = :(a + \$b)",
            ),
            goldenLines(
                "c «Operator|=» «String|'a'» «Operator|*» «String|'\\n'» «Operator|*» «String|'\\u00e9'» «Operator|*» «String|'😀'»",
                "«Type|B» «Operator|=» «Type|A»«Operator|'» «Operator|*» x«Punctuation|[»«Number|1»«Punctuation|]»«Operator|'» «Operator|+» «Punctuation|(»«Type|A» «Operator|.+» «Number|1»«Punctuation|)»«Operator|'»",
                "d «Operator|=» «Function|Dict»«Punctuation|(»«Constant|:key» «Operator|=>» «Number|1»«Punctuation|,» «Constant|:other!» «Operator|=>» «Number|2»«Punctuation|)»",
                "r «Operator|=» «Number|1»«Operator|:»n«Punctuation|;» s «Operator|=» a«Punctuation|[»«Number|2»«Operator|:»«Keyword|end»«Punctuation|];» t «Operator|=» x «Operator|?» «Constant|:yes» «Operator|:» «Constant|:no»",
                "q «Operator|=» «Operator|:»«Punctuation|(»a «Operator|+» «Operator|\$»b«Punctuation|)»",
            ),
        )
    }

    @Test
    fun macrosNumbersAndOperators() {
        assertGolden(
            "julia",
            goldenLines(
                "@time sum(x .^ 2)",
                "@assert isa(x, Int) \"msg\"",
                "Base.@kwdef struct Opts end",
                "n = 0x1F + 0b101 + 0o17 + 1_000 + 1.5e-3 + .5 + 2im + 1f0",
                "y = x ≤ 2 && z ∈ s || !(a ÷ b ≠ c)",
                "f = (x, y) -> x => y",
                "α₁ = √2; 🍕 = 1; push!(v, x...)",
            ),
            goldenLines(
                "«Function|@time» «Function|sum»«Punctuation|(»x «Operator|.^» «Number|2»«Punctuation|)»",
                "«Function|@assert» «Keyword|isa»«Punctuation|(»x«Punctuation|,» «Type|Int»«Punctuation|)» «String|\"msg\"»",
                "«Type|Base»«Punctuation|.»«Function|@kwdef» «Keyword|struct» «Type|Opts» «Keyword|end»",
                "n «Operator|=» «Number|0x1F» «Operator|+» «Number|0b101» «Operator|+» «Number|0o17» «Operator|+» «Number|1_000» «Operator|+» «Number|1.5e-3» «Operator|+» «Number|.5» «Operator|+» «Number|2im» «Operator|+» «Number|1f0»",
                "y «Operator|=» x «Operator|≤» «Number|2» «Operator|&&» z «Operator|∈» s «Operator|||» «Operator|!»«Punctuation|(»a «Operator|÷» b «Operator|≠» c«Punctuation|)»",
                "f «Operator|=» «Punctuation|(»x«Punctuation|,» y«Punctuation|)» «Operator|->» x «Operator|=>» y",
                "α₁ «Operator|=» «Operator|√»«Number|2»«Punctuation|;» 🍕 «Operator|=» «Number|1»«Punctuation|;» «Function|push!»«Punctuation|(»v«Punctuation|,» x«Operator|...»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun nestedBlockCommentsAcrossLines() {
        assertGolden(
            "julia",
            goldenLines(
                "x = 1 #= one",
                "#= two =# still one",
                "=# y = 2",
                "#= open",
                "z = \"not code\"",
            ),
            goldenLines(
                "x «Operator|=» «Number|1» «Comment|#= one»",
                "«Comment|#= two =# still one»",
                "«Comment|=#» y «Operator|=» «Number|2»",
                "«Comment|#= open»",
                "«Comment|z = \"not code\"»",
            ),
        )
    }

    @Test
    fun unterminatedStringsAndInterpolations() {
        assertGolden(
            "julia",
            goldenLines(
                "s = \"open \$(f(",
                "1)) end\"",
                "t = \"never closed",
                "u = 1",
            ),
            goldenLines(
                "s «Operator|=» «String|\"open »«Punctuation|\$(»«Function|f»«Punctuation|(»",
                "«Number|1»«Punctuation|))»«String| end\"»",
                "t «Operator|=» «String|\"never closed»",
                "«String|u = 1»",
            ),
        )
    }

    @Test
    fun theLineCapCutsInsideAString() {
        assertGolden(
            "julia",
            goldenLines(
                "s = \"abcdefghijklmnop\"",
                "x = 1\"",
                "y = 2",
            ),
            goldenLines(
                "s «Operator|=» «String|\"abcdefg»hijklmnop\"",
                "«String|x = 1\"»",
                "y «Operator|=» «Number|2»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "julia",
            "s = \"😀 \$x\"\r\n# \uD83D\r\nc = '\uDE00'\r\n",
            goldenLines(
                "s «Operator|=» «String|\"😀 »«Variable|\$x»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "c «Operator|=» «String|'⟨DE00⟩'»␍",
                "",
            ),
        )
    }
}
