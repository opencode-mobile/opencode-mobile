package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Elixir fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class ElixirGoldenTest {
    @Test
    fun modulesFunctionsAndInterpolation() {
        assertGolden(
            "elixir",
            goldenLines(
                "defmodule Sessions.Store do",
                "  @moduledoc false",
                "  @max 10",
                "  alias MyApp.{User, Repo}",
                "  def label(count, opts \\\\ []) when is_integer(count) do",
                "    \"sessions: #{count} #{opts[:x] <> \"#{@max}\"}\\n\"",
                "  end",
                "  defp valid?(%User{name: name} = u), do: name != nil",
                "  defmacro __using__(_opts), do: quote(do: import unquote(__MODULE__))",
                "end",
            ),
            goldenLines(
                "«Keyword|defmodule» «Type|Sessions»«Punctuation|.»«Type|Store» «Keyword|do»",
                "  «Variable|@moduledoc» «Constant|false»",
                "  «Variable|@max» «Number|10»",
                "  «Keyword|alias» «Type|MyApp»«Punctuation|.{»«Type|User»«Punctuation|,» «Type|Repo»«Punctuation|}»",
                "  «Keyword|def» «Function|label»«Punctuation|(»count«Punctuation|,» opts «Operator|\\\\» «Punctuation|[])» «Keyword|when» «Function|is_integer»«Punctuation|(»count«Punctuation|)» «Keyword|do»",
                "    «String|\"sessions: »«Punctuation|#{»count«Punctuation|}»«String| »«Punctuation|#{»opts«Punctuation|[»«Constant|:x»«Punctuation|]» «Operator|<>» «String|\"#{@max}\"»«Punctuation|}»«Constant|\\n»«String|\"»",
                "  «Keyword|end»",
                "  «Keyword|defp» «Function|valid?»«Punctuation|(%»«Type|User»«Punctuation|{»«Constant|name:» name«Punctuation|}» «Operator|=» u«Punctuation|),» «Constant|do:» name «Operator|!=» «Constant|nil»",
                "  «Keyword|defmacro» «Function|__using__»«Punctuation|(»_opts«Punctuation|),» «Constant|do:» «Keyword|quote»«Punctuation|(»«Constant|do:» «Keyword|import» «Keyword|unquote»«Punctuation|(»«Constant|__MODULE__»«Punctuation|))»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun atomsKeywordsAndPipes() {
        assertGolden(
            "elixir",
            goldenLines(
                "result = %{status: :ok, \"key\" => :\"quoted #{x}\", op: :+, mod: Enum}",
                "list |> Enum.map(&String.upcase/1) |> IO.inspect(label: \"out\")",
                "fn {:ok, v} -> v; _ -> nil end",
                "case map.key do",
                "  x when x in [1, 2] -> ?a + ?\\n + 0x1F + 1_000.5e-3",
                "end",
            ),
            goldenLines(
                "result «Operator|=» «Punctuation|%{»«Constant|status:» «Constant|:ok»«Punctuation|,» «String|\"key\"» «Operator|=>» «Constant|:\"quoted »«Punctuation|#{»x«Punctuation|}»«Constant|\"»«Punctuation|,» «Constant|op:» «Constant|:+»«Punctuation|,» «Constant|mod:» «Type|Enum»«Punctuation|}»",
                "list «Operator||>» «Type|Enum»«Punctuation|.»«Function|map»«Punctuation|(»«Operator|&»«Type|String»«Punctuation|.»«Function|upcase»«Operator|/»«Number|1»«Punctuation|)» «Operator||>» «Type|IO»«Punctuation|.»«Function|inspect»«Punctuation|(»«Constant|label:» «String|\"out\"»«Punctuation|)»",
                "«Keyword|fn» «Punctuation|{»«Constant|:ok»«Punctuation|,» v«Punctuation|}» «Operator|->» v«Punctuation|;» _ «Operator|->» «Constant|nil» «Keyword|end»",
                "«Keyword|case» map«Punctuation|.»«Property|key» «Keyword|do»",
                "  x «Keyword|when» x «Keyword|in» «Punctuation|[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|]» «Operator|->» «Number|?a» «Operator|+» «Number|?\\n» «Operator|+» «Number|0x1F» «Operator|+» «Number|1_000.5e-3»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun sigilsAndHeredocs() {
        assertGolden(
            "elixir",
            goldenLines(
                "~r/a\\/b#{x}/iu",
                "~S(raw (nested) #{no} \\n) <> ~s[a #{b}] <> ~w(one two)a",
                "~D[2024-01-01] <> ~c'chars' <> ~HTML|<p>|",
                "@doc \"\"\"",
                "Docs #{inspect(x)} \\t",
                "\"\"\"",
                "@doc ~S\"\"\"",
                "raw #{x}",
                "\"\"\"",
                "'charlist #{y}'",
            ),
            goldenLines(
                "«String|~r/a»«Constant|\\/»«String|b»«Punctuation|#{»x«Punctuation|}»«String|/iu»",
                "«String|~S(raw (nested) #{no} \\n)» «Operator|<>» «String|~s[a »«Punctuation|#{»b«Punctuation|}»«String|]» «Operator|<>» «String|~w(one two)a»",
                "«String|~D[2024-01-01]» «Operator|<>» «String|~c'chars'» «Operator|<>» «String|~HTML|<p>|»",
                "«Variable|@doc» «String|\"\"\"»",
                "«String|Docs »«Punctuation|#{»«Function|inspect»«Punctuation|(»x«Punctuation|)}»«String| »«Constant|\\t»",
                "«String|\"\"\"»",
                "«Variable|@doc» «String|~S\"\"\"»",
                "«String|raw #{x}»",
                "«String|\"\"\"»",
                "«String|'charlist »«Punctuation|#{»y«Punctuation|}»«String|'»",
            ),
        )
    }

    @Test
    fun multiLineInterpolationAndSigils() {
        assertGolden(
            "elixir",
            goldenLines(
                "s = \"a #{f(",
                "  1)} b",
                "c\"",
                "w = ~w[",
                "  one two",
                "]a",
            ),
            goldenLines(
                "s «Operator|=» «String|\"a »«Punctuation|#{»«Function|f»«Punctuation|(»",
                "  «Number|1»«Punctuation|)}»«String| b»",
                "«String|c\"»",
                "w «Operator|=» «String|~w[»",
                "«String|  one two»",
                "«String|]a»",
            ),
        )
    }

    @Test
    fun unterminatedConstructs() {
        assertGolden(
            "elixir",
            goldenLines(
                "s = \"open #{x",
                "y = ~s(never (closed)",
                "z = 1",
            ),
            goldenLines(
                "s «Operator|=» «String|\"open »«Punctuation|#{»x",
                "y «Operator|=» «String|~s(never (closed)»",
                "«String|z = 1»",
            ),
        )
    }

    @Test
    fun theLineCapCutsInsideASigil() {
        assertGolden(
            "elixir",
            goldenLines(
                "x = ~s(abcdefghij)",
                "y = 1)",
                "z = 2",
            ),
            goldenLines(
                "x «Operator|=» «String|~s(abc»defghij)",
                "«String|y = 1)»",
                "z «Operator|=» «Number|2»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "elixir",
            "s = \"😀 #{x}\"\r\n# \uD83D\r\n:a\uDE00\r\n",
            goldenLines(
                "s «Operator|=» «String|\"😀 »«Punctuation|#{»x«Punctuation|}»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "«Constant|:a»⟨DE00⟩␍",
                "",
            ),
        )
    }
}
