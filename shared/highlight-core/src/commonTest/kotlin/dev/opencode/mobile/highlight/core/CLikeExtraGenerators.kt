package dev.opencode.mobile.highlight.core

/**
 * Random pieces for the C-like lexer's Dart, Scala, Groovy, Objective-C, Zig, and Apex
 * configurations, merged into [HighlightGenerators]: interpolations, triple-quoted and raw
 * strings, slashy strings, `@` literals, Zig line strings, and SOQL queries appear opened, closed,
 * and damaged, next to CRLF, tabs, emoji, and lone surrogates.
 */
internal object CLikeExtraGenerators {
    val languages = listOf("dart", "scala", "groovy", "objective-c", "zig", "apex")

    private val common =
        listOf(
            "{", "}", "(", ")", "[", "]", ";", ",", ".", ":", " ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\r", "\\", "=", "/",
            "*", "<", ">", "?", "foo", "Bar", "MAX_X", "if", "return", "class", "true", "null", "123", "0x1F", "1.5e3", "// line",
            "/* c */", "/* open", "*/", "\"", "'", "\"s\\\"q\"", "\"open", "@Ann", "#", "$", "😀", "\uD83D", "\uDE00", "​", "é",
        )

    val pieces: Map<String, List<String>> =
        mapOf(
            "dart" to
                common +
                listOf(
                    "'s \$x \${y}'", "'", "\"\${", "\$name", "\${", "'''", "\"\"\"", "r'raw \$x'", "r\"", "r'''", "'\\n'", "#sym", "#a.b",
                    "/* /* nested */", "async", "await", "late", "final", "var", "int", "void", "@override", "#!", "get", "=>", "??",
                ),
            "scala" to
                common +
                listOf(
                    "s\"", "f\"", "raw\"", "s\"\"\"", "\"\"\"", "\"\"\"\"", "\$x", "\${", "\$\$", "'sym", "'c'", "'\\n'", "`type`", "def",
                    "val", "object", "trait", "given", "using", "match", "case", "=>", "<-", "::", "/* /* nested */", "@tailrec", "#!",
                ),
            "groovy" to
                common +
                listOf(
                    "\"g \$a.b \${c}\"", "\"\${", "\$x.y", "'''", "\"\"\"", "/slashy \$x/", "~/re\\/x/", "/", "\$/", "/\$", "\$/ds \$\$ \$//\$",
                    "\$\$", "def", "it", "->", "?:", "?.", "=~", "@Grab(", "#!", "a / b",
                ),
            "objective-c" to
                common +
                listOf(
                    "\n#import <Foundation/Foundation.h>", "\n#define X \\", "\n#if A", "\n#endif", "@\"s %@ %d\"", "@\"", "@[", "@{", "@(",
                    "@42", "@YES", "@interface", "@implementation", "@end", "@property", "@selector(", "\n- (void)", "\n+ (id)", "name:",
                    "[obj msg]", "[[A alloc] init]", "self", "nil", "NSString", "BOOL", "^(int x)", "\\\n",
                ),
            "zig" to
                common +
                listOf(
                    "\\\\", "\\\\line \"q\" // x", "\n    \\\\", "@import(", "@\"quoted id\"", "@\"open", "@", "u8", "i32", "u7", "usize",
                    "comptime", "fn", "pub", "const", "var", "error", "orelse", "catch", "'a'", "'\\n'", ".{", ".field", "0o17", "0x1.8p3",
                    "/// doc", "//!",
                ),
            "apex" to
                common +
                listOf(
                    "[SELECT Id FROM Account", "[select Name from Contact where X = :v]", "[FIND 'a' IN ALL FIELDS RETURNING Account]",
                    "[", "]", "[SELECT", "WHERE", "LIMIT 10", "LAST_N_DAYS:30", ":name", "'s\\'q'", "'open", "PUBLIC", "Integer", "string",
                    "trigger", "on", "before", "insert", "@AuraEnabled", "get;", "set;", "with sharing",
                ),
        )
}
