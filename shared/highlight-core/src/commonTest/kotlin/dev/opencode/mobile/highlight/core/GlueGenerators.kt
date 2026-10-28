package dev.opencode.mobile.highlight.core

/**
 * Random pieces for Lua, Perl, PowerShell, AWK, Make, and R, merged into [HighlightGenerators]:
 * every construct that spans lines appears opened, closed, and damaged, next to CRLF, tabs,
 * emoji, and lone surrogates.
 */
internal object GlueGenerators {
    val languages = listOf("lua", "perl", "powershell", "awk", "make", "r")

    private val common = listOf(" ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\\", "😀", "\uD83D", "\uDE00", "​", "é", "\r")

    val pieces: Map<String, List<String>> =
        mapOf(
            "lua" to
                common +
                listOf(
                    "#!/usr/bin/lua", "-- c", "--", "--[[", "--[==[", "]]", "]==]", "]=]", "[[", "[=[", "[==[", "[", "]", "=", "==", "~=",
                    "\"s\\\"q\"", "\"open", "'s'", "'", "\"", "\\z", "\\\n", "\"\\x41\\u{48}\\065\"", "local", "function", "end", "if",
                    "then", "return", "nil", "true", "self", "t.x", "obj:m(", "f(", "f{", "f\"", "(", ")", "{", "}", ",", ";", ".", "..",
                    "...", ":", "::lbl::", "#t", "0x1p4", "1.5e3", "42", "x", "print",
                ),
            "perl" to
                common +
                listOf(
                    "#!/usr/bin/perl", "# c", "#", "\n=pod\n", "\n=head1 X\n", "\n=cut\n", "=cut", "\n__END__\n", "\n__DATA__\n", "my",
                    "our", "sub ", "sub f {", "use strict;", "package Foo::Bar;", "\$x", "\$_", "@_", "@list", "%h", "\$#a", "\${x}", "\$1",
                    "\$h{key}", "\$r->{k}", "->", "=>", "=~", "!~", "/re/i", "/", "//", "m{", "m!", "s/a/b/g", "s{", "}{", "}", "{", "tr/a-z//",
                    "y", "q(", "qq{", "qw[", "qw(a b)", "qr/x/", "qx`", "(", ")", "[", "]", "\"s \$x @y\"", "\"open", "'q\\''", "'open",
                    "\"", "'", "`", "<<\"EOF\"", "<<'EOF'", "<<EOF", "<<~EOT", "\nEOF\n", "\n  EOT\n", "EOF", "<<", "<STDIN>", "<\$fh>",
                    "print", "join", "eq", "x", "1_000", "0x1F", "1.5e3", ";", ",", "%", "&f", "*g", "-s ", "\\@a",
                ),
            "powershell" to
                common +
                listOf(
                    "# c", "#", "<#", "#>", "<# c #>", "\$x", "\$env:Path", "\${a b}", "\$_", "\$true", "\$null", "@splat", "@(", "@{",
                    "\"s \$x\"", "\"", "\"open", "\"\"", "`n", "`", "'s'", "''", "'", "'open", "@\"", "\n\"@", "\"@", "@'", "\n'@", "'@",
                    "\"\$(Get-Date -f \"yyyy\")\"", "\$(", "(", ")", "{", "}", "[", "]", "[int]", "[string[]]", "[Parameter(Mandatory)]",
                    "function", "Get-ChildItem", "Write-Host", "-Name", "-eq", "-ceq", "-and", "if", "foreach", "param", "class",
                    "\$x.Name", ".Method(", "::", "|", "=", "+=", "42", "0x1F", "4kb", "1.5e3", ",", ";",
                ),
            "awk" to
                common +
                listOf(
                    "BEGIN", "END", "{", "}", "(", ")", "[", "]", ";", ",", "# c", "#", "\$1", "\$NF", "\$(", "NR", "FS", "print", "printf",
                    "if", "else", "function f(a, b)", "getline", "in", "length(", "gsub(", "x", "/re/", "/[/]/", "/", "\"s\\\"q\"", "\"open",
                    "\"cont\\\n", "\"", "~", "!~", "==", "&&", "++", "--", "=", "+=", "42", "1.5e3", "0x1F",
                ),
            "make" to
                common +
                listOf(
                    "\nall: build", "\n.PHONY: x", "\n%.o: %.c", "\nx:: y", "\na b &: c", ":", "::", "\n\t", "\t@echo \"hi \$@\"",
                    "\t-rm -f \$(OBJ)", "\tcd x && \$(MAKE)", "\t\$\$HOME", "CC = gcc", "CFLAGS += -O2", "X := \$(shell ls)", "Y ?= 1",
                    "Z != date", "\nifeq (\$(A),b)", "\nelse ifdef C", "\nendif", "\ninclude x.mk", "\n-include y", "\ndefine BODY",
                    "\nendef", "\nexport PATH", "\noverride V = 1", "\$(", "\${", "\$(VAR)", "\${V}", "\$@", "\$<", "\$^", "\$\$", "\$",
                    "\$(patsubst %.c,%.o,\$(SRC))", "\$(SRC:.c=.o)", "(", ")", ",", "|", ";", "# c", "#", "\\\n", "'", "\"", "=", "vpath",
                ),
            "r" to
                common +
                listOf(
                    "# c", "#", "<-", "<<-", "->", "->>", "=", "==", "|>", "%in%", "%>%", "%%", "%", "`odd name`", "`", "\"s\\\"q\"",
                    "\"open", "'s'", "'open", "\"", "'", "r\"(raw)\"", "R\"[", "]\"", "r'---{", "}---'", "r\"(", ")\"", "function(x, y = 2)",
                    "function", "\\(x)", "if", "else", "for", "in", "TRUE", "NA", "NULL", "Inf", "1L", "2i", "0x1F", "1e-3", ".5",
                    "df\$col", "x@slot", "pkg::fn(", ":::", "(", ")", "[[", "]]", "[", "]", "{", "}", ",", ";", "paste0(", "is.na",
                ),
        )
}
