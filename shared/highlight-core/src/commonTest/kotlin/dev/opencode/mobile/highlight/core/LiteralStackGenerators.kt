package dev.opencode.mobile.highlight.core

/**
 * Random pieces for the [LiteralStackLexer] languages (Julia, Crystal, Nim, CoffeeScript,
 * Elixir, Erlang), merged into [HighlightGenerators]: every construct that spans lines appears
 * opened, closed, and damaged, next to CRLF, tabs, emoji, and lone surrogates.
 */
internal object LiteralStackGenerators {
    val languages = listOf("julia", "crystal", "nim", "coffee", "elixir", "erlang")

    private val common =
        listOf(
            " ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\\", "😀", "\uD83D", "\uDE00", "\u200B", "é", "\r", "(", ")", "[", "]",
            "{", "}", ",", ";", ".", ":", "=", "\"", "'", "123", "0x1F", "1_000", "1.5e3", "x", "Foo", "MAX",
        )

    val pieces: Map<String, List<String>> =
        mapOf(
            "julia" to
                common +
                listOf(
                    "# c", "#", "#=", "=#", "#= a #= b =# c =#", "\"s \$x \$(y) \\n\"", "\"open", "\"\"\"", "\"\"\"\n  t \$z\n\"\"\"", "\$(",
                    "\$", "`cmd \$x`", "```", "r\"\\d\"i", "raw\"\"\"", "b\"x\"", "'a'", "'\\n'", "'😀'", "A'", "x'", "]'", "'", "::",
                    "::Int", "<:", ":sym", "1:n", "@time", "@Base.kwdef", "function f(", "end", "struct", "mutable struct", "where",
                    ".+", ".=", "f.(x)", "2im", "1f0", "α₁", "π", "≤", "∈", "push!", "true", "nothing", "->", "=>", "...",
                ),
            "crystal" to
                common +
                listOf(
                    "# c", "#", "\"s #{x} \\n\"", "#{", "\"open", "`ls`", "'a'", "'\\n'", "'", "%w(", "%i[", "%q{", "%Q<", "%r|", "%(",
                    "%", "/re/i", "/", ":sym", ":\"q\"", ":+", "::", " : ", "key: ", "<<-EOS", "<<-'EOS'", "\nEOS\n", "\n  EOS\n", "EOS",
                    "<<", "@[JSON::Field(", "@iv", "@@cv", "\$~", "def ", "self.", "end", "do", "|x|", "{{", "}}", "{%", "%}", "if",
                    "class", "nil", "1_i64", "2u8", "x.y", "&.", "=>", "->", "?", "valid?",
                ),
            "nim" to
                common +
                listOf(
                    "# c", "## doc", "#", "#[", "]#", "##[", "]##", "#[ a #[ b ]# c ]#", "\"s\\n\"", "\"open", "\"\"\"", "\"\"\"\"",
                    "\"\"\"\nt\n\"\"\"", "r\"a\"\"b\"", "r\"", "sql\"x\"", "fmt\"\"\"", "'a'", "'\\n'", "'", "{.", ".}", "{.inline.}",
                    "{.push x: off,", "`+`", "`", "proc ", "func ", "template", "proc `$`*(", "let", "var", "type", "object", "int",
                    "string", "seq[int]", "result", "echo", "0x1F'u8", "1'i32", "3u", "..", "..<", "&", "$", "@[", "*", "nil", "and",
                ),
            "coffee" to
                common +
                listOf(
                    "# c", "#", "###", "\n###\n", "####", "\"s #{x} \\n\"", "#{", "\"open", "'s'", "'open", "\"\"\"", "'''", "\"\"\"\n#{y}\n\"\"\"",
                    "/re/g", "/[/]/", "/", "///", "\n///\n", "/// a # c\n", "`js`", "```", "@x", "@", "->", "=>", "f = (a, b) ->",
                    "key: ->", "key:", "::", "this", "class", "extends", "yes", "null", "a?", "?.", "...", "..", "and", "isnt",
                ),
            "elixir" to
                common +
                listOf(
                    "# c", "#", "\"s #{x} \\n\"", "#{", "\"open", "'c'", "\"\"\"", "'''", "\"\"\"\n  #{y}\n  \"\"\"", "~r/a\\/b/i", "~s(",
                    "~S(a (b) c)", "~w[a b]a", "~r{", "~S\"\"\"", "~s\"\"\"\n#{z}\n\"\"\"", "~H", "~", ":ok", ":\"q #{x}\"", ":'a'", ":+",
                    "::", "key: ", "do:", "@attr", "@", "%{", "%User{", "?a", "?\\n", "?", "defmodule", "def ", "defp ", "do", "end",
                    "fn", "->", "|>", "<>", "\\\\", "&", "&1", "Enum.map(", "x.y", "nil", "__MODULE__",
                ),
            "erlang" to
                common +
                listOf(
                    "% c", "%%", "%", "\"s ~p~n\"", "\"open", "~", "\"\"\"", "\"\"\"\n  t\n  \"\"\"", "'atom'", "'open", "\$a", "\$\\n",
                    "\$\\x{41}", "\$", "\n-module(m).", "\n-export([f/1]).", "\n-define(X, 1).", "\n-record(r, {a}).", "-", "?MODULE",
                    "?", "#r{", "#r.f", "#{", "<<", ">>", "fun", "fun f/1", "->", "=>", ":=", "=:=", "case", "of", "end", "receive",
                    "when", "Var", "_", "atom", "mod:f(", "16#FF", "2#101", "|", "||",
                ),
        )
}
