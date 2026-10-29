package dev.opencode.mobile.highlight.core

/**
 * Random pieces for the older business and scientific languages (COBOL, Fortran fixed and free
 * form, Ada, Pascal, Visual Basic, ABAP, SAS), merged into [HighlightGenerators]: column-sensitive
 * line starts, every construct that spans lines opened, closed, and damaged, next to CRLF, tabs,
 * emoji, and lone surrogates.
 */
internal object ClassicGenerators {
    val languages = listOf("cobol", "fortran-fixed-form", "fortran-free-form", "ada", "pascal", "vb", "abap", "sas")

    private val common = listOf(" ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "😀", "\uD83D", "\uDE00", "​", "é", "\r", "(", ")", ",", ".", ";")

    val pieces: Map<String, List<String>> =
        mapOf(
            "cobol" to
                common +
                listOf(
                    "\n000100 ", "\n000200*", "\n000300/", "\n000400-", "\n000500D ", "\n       ", "\n      *", "\n      -    \"", "\n      \$SET ",
                    "\n>>SOURCE FORMAT FREE", "\n>>SOURCE FIXED", "      ", "123456", "IDENTIFICATION DIVISION.", "PROGRAM-ID.", "MAIN-PARA.",
                    "X SECTION.", "PROCEDURE", "DIVISION", "01", "88", "WS-X", "PIC", "PIC 9(5)V99", "PICTURE IS X(10).", "VALUE", "ZERO", "SPACES",
                    "HIGH-VALUES", "MOVE", "TO", "PERFORM", "THRU", "GO TO", "FUNCTION", "UPPER-CASE(", "\"lit\"", "'q''t'", "\"open", "'",
                    "\"", "X\"FF\"", "N'x'", "*>", "*> c", "3.14", "-", "**", ">=", "=", "&", "a".repeat(80), "STOP RUN.",
                ),
            "fortran-fixed-form" to
                common +
                listOf(
                    "\nC", "\nc ", "\n*", "\n!", "\n      ", "\n     1", "\n     +", "\n   10 ", "\n\t", "\n\t1", "\n#include \"x\"", "\nD ",
                    "      ", "     ", "PROGRAM", "INTEGER", "REAL*8", "DOUBLE PRECISION", "CALL", "SUB(", "DO 10 I=1,N", "CONTINUE", "END",
                    "'S''Q'", "'OPEN", "\"D\"", "'", "\"", "1.5D0", "1.E5", "1.EQ.2", ".AND.", ".TRUE.", "! c", "=", "**", "//", "*",
                    "X".repeat(70), "COMMON /B/", "&",
                ),
            "fortran-free-form" to
                common +
                listOf(
                    "program", "end program", "module", "subroutine", "function", "call", "use", "implicit none", "integer ::", "real(dp)",
                    "type(t)", "type ::", "class(*)", "data", "data =", "x%y", "%", "::", "=>", "=", "==", "/=", "**", "//", "(/", "/)",
                    "'s''q'", "\"d\"\"q\"", "'open", "\"open", "'", "\"", "&", " &", "&\n", "&\n  &", "! c", "!", "1.0_dp", "1.5d-3",
                    "1.eq.2", ".and.", ".true.", ".myop.", "z'FF'", "b\"01\"", "42_int64", ".5", "\n#ifdef X", "\n#endif", "sqrt(",
                ),
            "ada" to
                common +
                listOf(
                    "with", "use", "procedure", "function", "package body", "is", "begin", "end", "end if", "type", "subtype", "X", "Put_Line",
                    "(", "Integer", "True", "'", "X'First", "T'(", "'a'", "'''", "''", "\"s\"\"q\"", "\"open", "\"", "-- c", "--", "16#FF#",
                    "2#1.1#E4", "16#", "1_000", "3.14E-2", ":=", "=>", "..", "**", "/=", "<>", "&", "|", "all'Access", "😀'",
                ),
            "pascal" to
                common +
                listOf(
                    "program", "unit", "uses", "begin", "end", "procedure", "function", "TForm1.", "Click", "property", "read", "write",
                    "default", "Integer", "TObject", "EError", "nil", "'s''q'", "'open", "'", "''", "'''", "'''\n", "\n'''", "#13", "#\$0A",
                    "\$FF", "&17", "%101", "&begin", "{ c }", "{", "}", "{\$", "{\$IFDEF X}", "(*", "*)", "(*\$", "//", "// c", ":=", "..",
                    "<>", "@", "^", "1.5e3", "1..2", "Writeln(",
                ),
            "vb" to
                common +
                listOf(
                    "Sub", "Function", "End Sub", "Dim", "As", "New", "Of", "Integer", "Nothing", "True", "Me", "Call", "x", "Foo(", "' c",
                    "'", "REM c", "Rem", ":", "\"s\"\"q\"", "\"open", "\"", "\"x\"c", "$\"", "$\"a {", "{", "}", "{{", "}}", ":N2", "#",
                    "#1/1/2020#", "\n#If X Then", "\n#End If", "&HFF", "&O7", "&B1", "1_000", "1.5E3", "10UL", "_", " _\n", "<", ">",
                    "<Serializable>", "<Out>", "[Error]", "Left$(", "=", "<>", "&=", ":=", "?.", "‘",
                ),
            "abap" to
                common +
                listOf(
                    "\n*", "\n* c", "\"", "\" c", "DATA", "DATA:", "DATA(", "TYPES", "TYPE", "REF TO", "CLASS", "DEFINITION", "METHOD",
                    "METHODS", "FIELD-SYMBOLS", "BEGIN OF", "END OF", "VALUE", "#(", "NEW", "lines(", "x", "ls-comp", "sy-subrc", "->",
                    "=>", "~", "<fs>", "<fs>-x", "@lv", "@DATA(", "/ns/cl", "/", "'s''q'", "'open", "'", "`b``t`", "`", "|", "|t {",
                    "}", "{", "\\|", "\\", "##PRAGMA", ":", ",", "=", "<>", "&&", "?=", "123", "abap_true", "string", "lt", "count",
                ),
            "sas" to
                common +
                listOf(
                    "data", "set", "run;", "quit;", "proc", "sort", "sql", "if", "then", "do;", "end;", "label =", "input(", "cats(", "x",
                    "*", "* c", "%*", "%* c;", "/*", "*/", "/* c */", "'s''q'", "'open", "'", "\"d &x. q\"", "\"open", "\"", "'01jan2020'd",
                    "'1:00't", "'n'n", "&x", "&&x.", "&", "%let", "%macro", "m", "%mend", "%put", "%sysfunc(", "%m(", "datalines;", "\ndatalines;\n",
                    "cards4;", "\n;;;;\n", "\n1 2\n", ".", ".A", "0FFx", "1.5e3", "=", "**", "||", "^=", "<>",
                ),
        )
}
