package dev.opencode.mobile.highlight.core

/**
 * Random pieces for the scripting and config languages (shell, YAML, TOML, Dockerfile, SQL,
 * Ruby), merged into [HighlightGenerators]: every construct that spans lines appears opened,
 * closed, and damaged, next to CRLF, tabs, emoji, and lone surrogates.
 */
internal object ScriptingGenerators {
    val languages = listOf("shellscript", "yaml", "toml", "docker", "sql", "ruby")

    private val common = listOf(" ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\\", "😀", "\uD83D", "\uDE00", "\u200B", "é", "\r")

    val pieces: Map<String, List<String>> =
        mapOf(
            "shellscript" to
                common +
                listOf(
                    "#!/bin/sh", "# note", "#", "'", "\"", "`", "$", "\$x", "${'$'}{x:-d}", "${'$'}{", "}", "$(", "$((", "))", "(", ")", "((",
                    "'s q'", "\"d \$y\"", "\$'a\\tb'", "\$\"", "<<EOF", "<<-EOF", "<<'EOF'", "<< \"E O\"", "\nEOF\n", "\n\tEOF\n", "EOF",
                    "<<<", "<<", "|", "||", "&&", ";", ";;", "&", ">", "2>&1", "&>", "if", "then", "fi", "for", "in", "do", "done", "case",
                    "esac", "function", "export", "local", "X=1", "a[0]=", "+=", "-x", "--long=v", "--", "[[", "]]", "{", "echo", "cd",
                    "123", "$ ", "\\\n", "\$@", "\$1", "#{",
                ),
            "yaml" to
                common +
                listOf(
                    "---", "...", "%YAML 1.2", "key:", "key: ", " value", "- ", "-", "? ", ": ", ":", "#", " # c", "&a", "*a", "!t",
                    "!!str", "|", "|-", ">+", "|2", "\n  text", "\n    deeper", "\n", "[", "]", "{", "}", ",", "\"q\\\"x\"", "\"open",
                    "'s''q'", "'open", "\"", "'", "true", "null", "~", "yes", "42", "-1.5e3", "0x1F", ".inf", ".nan", "url: http://x:1",
                    "  ", "    ",
                ),
            "toml" to
                common +
                listOf(
                    "[t]", "[[a]]", "[", "]", "[[", "]]", "{", "}", "=", ",", ".", "key", "a.b", "\"q k\"", "'lit'", "\"s\\u00e9\"", "\"open",
                    "'open", "\"\"\"", "'''", "\"\"\"\"\"", "\\\n", "# c", "#", "true", "false", "inf", "nan", "+inf", "1_000", "0xFF",
                    "0o17", "0b1", "1e6", "-3.14", "1979-05-27", " 07:32:00", "T07:32:00Z", "07:32:00",
                ),
            "docker" to
                common +
                listOf(
                    "# syntax=docker/dockerfile:1", "# escape=`", "# escape=\\", "#", "\nFROM ", "FROM", "AS", "RUN ", "\nRUN ", "CMD",
                    "ENTRYPOINT", "ENV ", "ARG ", "LABEL ", "COPY", "--from=b", "--mount=type=cache", "HEALTHCHECK", "ONBUILD", "NONE",
                    "[", "]", ",", "\"exec\"", "'s'", "\"", "'", "${'$'}{V}", "\$V", "\$", "\\\n", "`\n", "&&", "<<EOF", "\nEOF\n",
                    "K=v", "=", "8080", "echo \"\$(x)\"", "\n# c\n", "\n\n",
                ),
            "sql" to
                common +
                listOf(
                    "SELECT", "from", "Where", "AND", "INT", "varchar(", "NULL", "true", "COUNT(", "left(", "(", ")", ",", ";", ".", "t.x",
                    "'s''q'", "'open", "'", "E'\\''", "N'x'", "\"Id\"", "\"open", "`c`", "`", "[col]", "[", "]", "$1", "$$", "\$tag\$",
                    "\$a\$", "$", "?", ":name", "::", "@v", "@@g", "-- c", "--", "/* c */", "/*", "*/", "123", "1.5e3", "0xFF", "=", "<>",
                    "||",
                ),
            "ruby" to
                common +
                listOf(
                    "# c", "#", "=begin", "\n=begin\n", "\n=end\n", "=end", "\n__END__\n", "\"", "'", "`", "\"s #{x} \\n\"", "#{", "}", "{",
                    "'r\\''", "%w[", "%q(", "%Q{", "%i<", "%r!", "%(", "%", "(", ")", "[", "]", "<", ">", "!", "|", "/", "/re/i", "?a", "?",
                    ":sym", ":\"s\"", ":+", ":", "::", "key:", "<<~EOS", "<<-'EOS'", "<<EOS", "\nEOS\n", "\n  EOS\n", "EOS", "<<", "@iv",
                    "@@cv", "\$g", "\$!", "def ", "self.", "end", "do", "|x|", "if", "class", "Foo", "MAX", "nil", "1_000", "1.5e3", "2r",
                    "x.y", "&.", "=>", "->", "==", "#@x", "\\\n",
                ),
        )
}

/** Joins golden lines with `\n`, for fixtures written one escaped string per line. */
internal fun goldenLines(vararg lines: String): String = lines.joinToString("\n")
