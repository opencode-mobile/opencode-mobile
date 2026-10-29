package dev.opencode.mobile.highlight.core

/**
 * Random pieces for the functional and symbolic languages (Haskell, OCaml, F#, Clojure, Common
 * Lisp, Prolog, Smalltalk), merged into [HighlightGenerators]: every construct that spans lines
 * appears opened, closed, nested, and damaged, next to CRLF, tabs, emoji, and lone surrogates.
 */
internal object SymbolicGenerators {
    val languages = listOf("haskell", "ocaml", "fsharp", "clojure", "common-lisp", "prolog", "smalltalk")

    private val common = listOf(" ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\\", "😀", "\uD83D", "\uDE00", "​", "é", "\r", "(", ")", "[", "]", "{", "}")

    val pieces: Map<String, List<String>> =
        mapOf(
            "haskell" to
                common +
                listOf(
                    "-- c", "--", "-->", "{-", "-}", "{- {- n -} -}", "{-#", "#-}", "{-# LANGUAGE X #-}", "\"s\\\"q\"", "\"open", "\"gap\\",
                    "\\  \\", "\"", "'a'", "'\\n'", "'", "x'", "foldl'", "''T", "[q|", "|]", "[e|", "[|", "[x|x<-xs]", "`div`", "`",
                    "::", "->", "<-", "=>", "=", "|", "\\x", ".", "Data.Map.lookup", "M.x", "Just", "True", "where", "let", "in", "case",
                    "of", "do", "import qualified", " as ", "module", "data", "class", "instance", "main", "f", "0x1F", "1.5e3", "1_000",
                    ",", ";",
                ),
            "ocaml" to
                common +
                listOf(
                    "(*", "*)", "(* (* n *) *)", "(*)", "( * )", "\"s\\\"q\"", "\"open", "\"", "{|", "|}", "{id|", "|id}", "{x|y|x}",
                    "'a'", "'\\n'", "'a", "'", "x'", "`Tag", "`", "~x", "~f:", "?opt", "let", "let rec", "and", "in", "match", "with",
                    "|", "->", "fun", "type", "type t =", "module", "M.f", "List.map", "r.field", "Some", "true", "mod", "[@@deriving show]",
                    "[@", "[%ext", ";;", ";", ",", "=", ":", "::", ":=", "0x1F", "1.", "1.5e3", "1_000L", "val", "external",
                ),
            "fsharp" to
                common +
                listOf(
                    "//", "/// doc", "(*", "*)", "(* (* n *) *)", "(*)", "\"s\\\"q\"", "\"open", "\"", "@\"v\"\"q\"", "@\"", "\"\"\"",
                    "\"\"\"t\"\"\"", "${'$'}\"", "${'$'}\"a {x} {{y}}\"", "${'$'}@\"", "@${'$'}\"", "${'$'}\"\"\"", "${'$'}${'$'}\"\"\"",
                    "{{", "}}", "{", "}", "\"a\"B", "'a'", "'\\n'", "'T", "'", "[<", ">]", "[<Literal>]", "[<A(1); B>]", "[|", "|]",
                    "let", "let!", "use!", "do!", "return!", "yield!", "match!", "async {", "task", "seq", "member this.X", "this.",
                    "override", "type", "module", "open", "x.Length", "List.map", "Console.WriteLine(", "true", "null", "#if DEBUG",
                    "\n#endif", "->", "|>", "<-", "=", ":", ":>", "1uy", "1.5m", "0x1F", "``a b``", "``", ",", ";", "$", "@",
                ),
            "clojure" to
                common +
                listOf(
                    "; c", ";", "#_", "#_(a (b))", "#_x", "\"s\\\"q\"", "\"open", "\"", "#\"re\\d\"", "#\"", "\\a", "\\newline", "\\",
                    ":kw", "::kw", ":ns/kw", ":", "(", "(defn", "(defn f", "(def x", "(defrecord R", "(let", "(if", "(str", "(->",
                    "#{", "#(", "%", "%1", "#'", "#?", "#?@", "#inst", "##Inf", "#:a{", "#", "^:private", "^String", "^{", "^", "'", "`",
                    "~", "~@", "@", "42", "-1", "1/3", "1.5M", "42N", "0x1F", "2r1010", "nil", "true", "String", "a.b/c", ",",
                ),
            "common-lisp" to
                common +
                listOf(
                    "; c", ";", "#|", "|#", "#| #| n |# |#", "\"s\\\"q\"", "\"open", "\"", "#\\a", "#\\Space", "#\\", ":kw", "#:g",
                    "&optional", "&key", "(", "(defun", "(defun f (x y)", "(defmacro m", "(defvar *x*", "(defclass c", "(lambda (a)",
                    "(let", "(if", "(format", "#'", "#'car", "#(", "#+sbcl", "#-", "#x1F", "#b101", "#36rZZ", "#1=", "#p\"", "#.", "'",
                    "`", ",", ",@", "|sym|", "|", "nil", "t", "1/3", "1.5d0", "-42", "+", "1+", "pkg:sym", "pkg::sym",
                ),
            "prolog" to
                common +
                listOf(
                    "% c", "%", "/*", "*/", "/* c */", "'q''a'", "'open", "'cont\\", "'", "\"s\"", "\"", "`c`", "`", "0'c", "0'\\n",
                    "0''", "0'", "0x1F", "1.5e3", "1_000", "X", "_", "_X", "foo", "foo(", "bar(X, Y)", ":-", ":- module(", "-->", "?-",
                    "is", "!", ";", "->", "\\+", "=..", "==", "=:=", ".", ". ", ",", "|", "true", "fail", "mod", "\\x41\\",
                ),
            "smalltalk" to
                common +
                listOf(
                    "\"c\"", "\"", "\"open", "'s''q'", "'open", "'", "\$a", "\$'", "\$", "#sym", "#at:put:", "#+", "#'q s'", "#(", "#[",
                    "#{", "#", "##x", "at:", "put:", "x:", ":x", ":=", "^", "self", "super", "nil", "true", "thisContext", "Object",
                    "foo", "|", ".", ";", ",", ">>", "->", "16r1F", "2r101", "1.5s2", "1e10", "-5", "3.14",
                ),
        )
}
