package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.ClassicScan.isWordPart
import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit

/**
 * ABAP, classic and ABAP Objects.
 *
 * Roles: keywords, hyphenated ones included (`FIELD-SYMBOLS`, `START-OF-SELECTION`,
 * `MOVE-CORRESPONDING`), are Keyword in any case, unless followed at once by `(`, which makes a
 * built-in function call (`lines( itab )`), except for constructor operators (`VALUE`, `NEW`,
 * `CONV`, `COND`, ...). The name a declaration introduces, first in the statement or after the
 * chain `:` and each `,`, takes the declaration's role: Variable after `DATA`, `CONSTANTS`,
 * `PARAMETERS`, `FIELD-SYMBOLS`, and inline `DATA(...)`; Type after `TYPES`, `CLASS`, and
 * `INTERFACE`; Function after `METHODS`, `METHOD`, `FORM`, `PERFORM`, and `EVENTS`. The name after
 * `TYPE` (past `REF TO`, `STANDARD TABLE OF`, ...), the type a constructor operator builds
 * (`VALUE ty_rows( )`), and built-in types (`string`, `abap_bool`) are Type. A word in a name slot
 * is a name even when it spells a keyword (`DATA(lt)`, `CONSTANTS lines ...`), except for the
 * words that continue a declaration (`BEGIN OF`, `END OF`, `INCLUDE TYPE`); between `BEGIN OF s`
 * and `END OF s` the component names stay plain. `abap_true`,
 * `abap_false`, and `space` are Constant. A component after `-` (`ls_row-name`, `sy-subrc`) or
 * after `->`, `=>`, `~` is Property, and Function when `(` follows at once, as is any name
 * directly before `(`. Field symbols (`<fs>`) and host variables (`@lv`) are Variable.
 * `'...'` and backtick literals are String with a doubled quote as Constant; in a string template
 * `|...{ expr }...|` the text is String, escapes (`\|`, `\{`, `\n`) Constant, and `{ }` Punctuation
 * around code. Numbers are Number. A `*` in column 1 makes the line a Comment, as does `"` to the
 * line end; `##PRAGMA` is Attribute. Operators (`=`, `<>`, `->`, `=>`, `?=`, `&&`, `#`, ...) are
 * Operator; `( ) [ ] , . :` are Punctuation, the `.` ending a statement.
 *
 * States: what a line hands the next is packed into bits. [DEPTH_MASK] counts the string
 * templates whose embedded expression is still open (template text itself ends with its line);
 * [MID_STATEMENT] marks a statement under way; [DECLARATION_SHIFT] holds the role a chained
 * declaration gives the names after `,`; [NAME_SHIFT] the role of the very next name; and
 * [TYPE_NEXT] that a name after `TYPE` is still to come; [STRUCTURE_SHIFT] how many `BEGIN OF`
 * structures are open. A `.` resets all but the depth.
 *
 * Known gaps: comparison words used as names elsewhere (`lines( lt )`) read as keywords; a string template is
 * String up to its line end when it is not closed there.
 */
internal object AbapLexer : HighlightLexer() {
    private const val DEPTH_MASK = 7
    private const val MID_STATEMENT = 8
    private const val DECLARATION_SHIFT = 4
    private const val NAME_SHIFT = 6
    private const val TYPE_NEXT = 256
    private const val STRUCTURE_SHIFT = 9
    private const val MAX_STRUCTURE = 3
    private const val ROLE_BITS = 3

    // Declared-name roles, two bits each in the state.
    private const val NAMES_VARIABLE = 1
    private const val NAMES_TYPE = 2
    private const val NAMES_FUNCTION = 3

    // Word kinds: the low 2 bits are the kind, then flags.
    private const val KEYWORD = 1
    private const val TYPE = 2
    private const val CONSTANT = 3
    private const val KIND_MASK = 3
    private const val DECLARES_SHIFT = 2
    private const val INLINE = 16
    private const val STARTS_TYPE = 32
    private const val CONSTRUCTOR = 64

    /** Stays a keyword where a declaration expects a name (`DATA: BEGIN OF ...`). */
    private const val IN_DECLARATION = 128

    /** `BEGIN` and `END` of a structure in a declaration. */
    private const val OPENS_STRUCTURE = 256
    private const val CLOSES_STRUCTURE = 512

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        if (start < end && text[start] == '*') {
            out.span(start, end, RoleCode.COMMENT)
            return state
        }
        return Line(text, start, end, out, state).run()
    }

    private class Line(
        val text: CharArray,
        val start: Int,
        val end: Int,
        val out: SpanSink,
        state: Int,
    ) {
        var index = start
        var depth = state and DEPTH_MASK
        var inText = false
        var mid = state and MID_STATEMENT != 0
        var declaration = (state ushr DECLARATION_SHIFT) and ROLE_BITS
        var nameNext = (state ushr NAME_SHIFT) and ROLE_BITS
        var typeNext = state and TYPE_NEXT != 0

        /** How many `BEGIN OF` structures of the declaration are open: their components stay plain. */
        var structure = (state ushr STRUCTURE_SHIFT) and MAX_STRUCTURE
        var afterArrow = false

        /** Right after `DATA(`: the next word is a name even if it spells a keyword. */
        var inlineName = false

        fun run(): Int {
            while (index < end && !out.stopped) {
                if (inText) templateText() else code()
            }
            // Template text never continues past its line.
            if (inText) {
                depth -= 1
                inText = false
            }
            return depth or (if (mid) MID_STATEMENT else 0) or (declaration shl DECLARATION_SHIFT) or (nameNext shl NAME_SHIFT) or
                (if (typeNext) TYPE_NEXT else 0) or (structure shl STRUCTURE_SHIFT)
        }

        fun templateText() {
            var segment = index
            while (index < end) {
                val char = text[index]
                when {
                    char == '\\' -> {
                        out.span(segment, index, RoleCode.STRING)
                        val after = if (index + 1 < end) index + 2 else index + 1
                        out.span(index, after, RoleCode.CONSTANT)
                        index = after
                        segment = index
                    }
                    char == '|' -> {
                        out.span(segment, index + 1, RoleCode.STRING)
                        index += 1
                        depth -= 1
                        inText = false
                        return
                    }
                    char == '{' -> {
                        out.span(segment, index, RoleCode.STRING)
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                        inText = false
                        return
                    }
                    else -> index += 1
                }
            }
            out.span(segment, end, RoleCode.STRING)
        }

        fun code() {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            val wasAfterArrow = afterArrow
            if (char == ' ' || char == '\t') {
                index += 1
                return
            }
            afterArrow = false
            val wasMid = mid
            mid = true
            when {
                char == '"' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                    mid = wasMid
                }
                char == '\'' || char == '`' -> {
                    val close = ClassicScan.doubledQuoteString(text, index, index + 1, end, char, out)
                    index = if (close < 0) end else close
                }
                char == '|' -> {
                    if (depth < DEPTH_MASK) {
                        out.span(index, index + 1, RoleCode.STRING)
                        index += 1
                        depth += 1
                        inText = true
                    } else {
                        // Too deep to track: the template to its close is String.
                        var close = index + 1
                        while (close < end && text[close] != '|') close += 1
                        val after = if (close < end) close + 1 else end
                        out.span(index, after, RoleCode.STRING)
                        index = after
                    }
                }
                char == '}' && depth > 0 -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    inText = true
                }
                char == '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    mid = false
                    declaration = 0
                    nameNext = 0
                    typeNext = false
                    structure = 0
                }
                char == ':' || char == ',' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    if (declaration != 0) nameNext = if (structure > 0) 0 else declaration
                    typeNext = false
                }
                char == '#' && next == '#' -> {
                    var after = index + 2
                    while (after < end && isWordPart(text[after])) after += 1
                    out.span(index, after, RoleCode.ATTRIBUTE)
                    index = after
                }
                isDigit(char) -> {
                    var after = index + 1
                    while (after < end && isDigit(text[after])) after += 1
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                }
                char == '<' && fieldSymbolEnd(index) > 0 -> {
                    val after = fieldSymbolEnd(index)
                    out.span(index, after, RoleCode.VARIABLE)
                    if (nameNext != 0) nameNext = 0
                    index = components(after)
                }
                char == '@' && (isAsciiLetter(next) || next == '_') -> {
                    val after = ClassicScan.wordEnd(text, index + 1, end)
                    if (words.lookup(text, index + 1, after) and KIND_MASK == KEYWORD) {
                        out.span(index, index + 1, RoleCode.OPERATOR)
                        index += 1
                    } else {
                        out.span(index, after, RoleCode.VARIABLE)
                        index = components(after)
                    }
                }
                ClassicScan.isLetter(char) || char == '_' -> word(index, wasMid, wasAfterArrow)
                char == '/' && namespaceEnd(index) > 0 -> word(index, wasMid, wasAfterArrow)
                char == '(' || char == ')' || char == '[' || char == ']' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                char == '-' && next == '>' || char == '=' && next == '>' -> {
                    val after = if (char == '-' && index + 2 < end && text[index + 2] == '*') index + 3 else index + 2
                    out.span(index, after, RoleCode.OPERATOR)
                    afterArrow = after == index + 2
                    index = after
                }
                char == '~' -> {
                    out.span(index, index + 1, RoleCode.OPERATOR)
                    index += 1
                    afterArrow = true
                }
                isOperator(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperator(text[after])) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }

        /** A word at [from] (a keyword, a name, or a `/ns/` name), with its `-` components. */
        fun word(
            from: Int,
            wasMid: Boolean,
            afterArrow: Boolean,
        ) {
            val first = if (text[from] == '/') namespaceEnd(from) else ClassicScan.wordEnd(text, from, end)
            var compound = first
            while (compound + 1 < end && text[compound] == '-' && isWordPart(text[compound + 1])) compound = ClassicScan.wordEnd(text, compound + 1, end)
            var kind = 0
            var after = first
            val forcedName = inlineName
            inlineName = false
            if (!afterArrow) {
                val whole = words.lookup(text, from, compound)
                // `ls_row-name` is a name and its components, also when its first part is a keyword.
                if (compound == first || whole and KIND_MASK == KEYWORD) {
                    kind = whole
                    after = compound
                }
                // `DATA(lt)`, `DATA lines TYPE i`: a declared name, unless it continues the declaration.
                if (forcedName || (nameNext != 0 && kind and KIND_MASK == KEYWORD && kind and IN_DECLARATION == 0)) {
                    kind = 0
                    after = first
                }
            }
            val call = after < end && text[after] == '('
            when {
                kind and KIND_MASK == KEYWORD && !(call && kind and (CONSTRUCTOR or INLINE) == 0) -> {
                    out.span(from, after, RoleCode.KEYWORD)
                    val declares = (kind ushr DECLARES_SHIFT) and ROLE_BITS
                    if (!wasMid && declares != 0) {
                        declaration = declares
                        nameNext = declares
                    }
                    if (kind and INLINE != 0 && call) {
                        nameNext = NAMES_VARIABLE
                        inlineName = true
                    }
                    if (kind and STARTS_TYPE != 0) typeNext = true
                    // `BEGIN OF name`, `END OF name`: the structure's own name takes the declaration's role.
                    if (declaration != 0 && kind and OPENS_STRUCTURE != 0) {
                        if (structure < MAX_STRUCTURE) structure += 1
                        nameNext = declaration
                    }
                    if (declaration != 0 && kind and CLOSES_STRUCTURE != 0 && structure > 0) {
                        structure -= 1
                        nameNext = declaration
                    }
                    // `VALUE ty_rows( ... )`: a constructor operator names the type it builds.
                    if (kind and CONSTRUCTOR != 0 && !call && constructsType(after)) typeNext = true
                }
                kind and KIND_MASK == KEYWORD -> out.span(from, after, RoleCode.FUNCTION)
                else -> {
                    val role =
                        when {
                            afterArrow -> if (call) RoleCode.FUNCTION else RoleCode.PROPERTY
                            typeNext && call -> RoleCode.TYPE
                            call -> RoleCode.FUNCTION
                            nameNext != 0 -> roleOf(nameNext)
                            typeNext -> RoleCode.TYPE
                            kind and KIND_MASK == TYPE -> RoleCode.TYPE
                            kind and KIND_MASK == CONSTANT -> RoleCode.CONSTANT
                            else -> 0
                        }
                    if (role != 0) out.span(from, after, role)
                    if (!afterArrow) {
                        nameNext = 0
                        typeNext = false
                    }
                }
            }
            index = components(after)
        }

        /** A name right before `(` follows a constructor operator at [after]: the type it builds. */
        fun constructsType(after: Int): Boolean {
            val at = LexerScan.skipBlanks(text, after, end)
            if (at == after || at >= end || !(ClassicScan.isLetter(text[at]) || text[at] == '_')) return false
            val wordEnd = ClassicScan.wordEnd(text, at, end)
            return wordEnd < end && text[wordEnd] == '('
        }

        /** `-name` components after a name at [from]: Punctuation `-` and Property names. */
        fun components(from: Int): Int {
            var at = from
            while (at + 1 < end && text[at] == '-' && isWordPart(text[at + 1])) {
                out.span(at, at + 1, RoleCode.PUNCTUATION)
                val after = ClassicScan.wordEnd(text, at + 1, end)
                out.span(at + 1, after, if (after < end && text[after] == '(') RoleCode.FUNCTION else RoleCode.PROPERTY)
                at = after
            }
            return at
        }

        /** The end of `<name>` at [at], or -1. */
        fun fieldSymbolEnd(at: Int): Int {
            var index = at + 1
            if (index >= end || !(ClassicScan.isLetter(text[index]) || text[index] == '_')) return -1
            index = ClassicScan.wordEnd(text, index, end)
            return if (index < end && text[index] == '>') index + 1 else -1
        }

        /** The end of a namespaced name `/ns/name` at [at], or -1. */
        fun namespaceEnd(at: Int): Int {
            val close = ClassicScan.wordEnd(text, at + 1, end)
            if (close == at + 1 || close >= end || text[close] != '/' || close + 1 >= end || !isWordPart(text[close + 1])) return -1
            return ClassicScan.wordEnd(text, close + 1, end)
        }

        fun roleOf(declared: Int): Int =
            when (declared) {
                NAMES_VARIABLE -> RoleCode.VARIABLE
                NAMES_TYPE -> RoleCode.TYPE
                else -> RoleCode.FUNCTION
            }

        fun isOperator(char: Char): Boolean =
            when (char) {
                '=', '<', '>', '+', '-', '*', '/', '&', '?', '#', '@' -> true
                else -> false
            }
    }

    private fun declares(role: Int): Int = KEYWORD or (role shl DECLARES_SHIFT)

    init {
        words.register(
            KEYWORD,
            "abstract", "add", "alias", "all", "and", "append", "appending", "as", "ascending", "assign", "assigned",
            "assigning", "at", "authority-check", "back", "between", "binary", "bit-and", "bit-not",
            "bit-or", "bit-xor", "blocks", "bound", "break-point", "bt", "by", "byte", "ca", "call", "case", "casting",
            "catch", "changing", "check", "cleanup", "clear", "close", "cn", "co", "collect", "commit", "comparing",
            "components", "concatenate", "condense", "context", "continue", "cp", "create", "cs", "currency",
            "decimals", "default", "define", "definition", "delete", "deleting", "descending", "describe",
            "destination", "div", "do", "duration", "else", "elseif", "end-of-definition", "end-of-page",
            "end-of-selection", "endat", "endcase", "endclass", "enddo", "endform", "endfunction", "endif",
            "endinterface", "endloop", "endmethod", "endmodule", "endon", "endselect", "endtry", "endwhile", "entries",
            "eq", "except", "exception", "exceptions", "exit", "exporting", "fields", "find", "first", "for", "format",
            "free", "from", "function-pool", "ge", "get", "group", "gt", "handler", "hashed", "having", "if",
            "implementation", "import", "importing", "in", "index", "inheriting", "initial",
            "initialization", "inner", "insert", "instance", "interfaces", "into", "is", "join", "key", "last", "le",
            "leave", "left", "length", "let", "level", "line", "lines", "load-of-program", "loop", "lower", "lt",
            "matches", "message", "mod", "modify", "module", "move", "move-corresponding", "multiply", "na", "nb", "ne",
            "next", "no-gap", "no-zero", "non-unique", "not", "np", "ns", "object", "obligatory", "occurrences",
            "occurrence", "occurs", "offset", "on", "open", "optional", "or", "order", "others", "output",
            "outer", "overlay", "package", "preferred", "private", "program", "protected", "public", "raise",
            "raising", "range", "read", "read-only", "receive", "receiving", "redefinition", "refresh", "regex",
            "reject", "replace", "report", "respecting", "results", "retry", "return", "returning", "right", "risk",
            "rollback", "rows", "screen", "search", "section", "select", "selection-screen", "set", "shift", "single",
            "skip", "sort", "sorted", "split", "standard", "start-of-selection", "static", "stop", "submit",
            "subtract", "super", "supplied", "table", "testing", "then", "to", "top-of-page", "transporting", "try",
            "uline", "unassign", "unique", "up", "update", "upper", "using", "when", "where", "while", "width", "with",
            "write", "me", "distinct", "ignoring", "pcre", "match", "until", "base", "checkbox", "radiobutton",
            "align", "pad", "iso", "alpha", "exponent", "timestamp", "timezone", "xsd",
        )
        words.register(declares(NAMES_VARIABLE), "constants", "statics", "parameters", "parameter", "field-symbols", "select-options", "ranges", "tables", "class-data")
        words.register(declares(NAMES_VARIABLE) or INLINE, "data", "final")
        words.register(declares(NAMES_VARIABLE) or INLINE or CONSTRUCTOR, "field-symbol")
        words.register(declares(NAMES_TYPE), "types", "class", "interface", "type-pools")
        words.register(declares(NAMES_FUNCTION), "methods", "class-methods", "method", "form", "perform", "events", "class-events", "function", "aliases")
        words.register(KEYWORD or STARTS_TYPE or IN_DECLARATION, "type")
        words.register(KEYWORD or IN_DECLARATION or OPENS_STRUCTURE, "begin")
        words.register(KEYWORD or IN_DECLARATION or CLOSES_STRUCTURE, "end")
        words.register(KEYWORD or IN_DECLARATION, "of", "enum", "structure", "common", "part", "include")
        words.register(
            KEYWORD or CONSTRUCTOR,
            "value", "new", "conv", "cond", "switch", "corresponding", "cast", "ref", "exact", "reduce", "filter",
        )
        words.register(
            TYPE,
            "string", "xstring", "abap_bool", "decfloat16", "decfloat34", "int1", "int2", "int4", "int8", "utclong",
            "any", "clike", "csequence", "numeric", "xsequence", "simple",
        )
        words.register(CONSTANT, "abap_true", "abap_false", "abap_undefined", "space")
    }
}
