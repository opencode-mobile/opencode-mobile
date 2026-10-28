package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isAsciiLetter
import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.isHexDigit

/**
 * SQL across the common dialects (standard, PostgreSQL, MySQL, SQLite, SQL Server), leniently.
 *
 * Roles: keywords from a broad shared set are Keyword in any case; data types are Type;
 * `TRUE`, `FALSE`, `NULL`, and `UNKNOWN` are Constant; any other name before `(` (and the
 * niladic `CURRENT_DATE`-style functions) is Function, except a table name right after
 * `INTO`, `TABLE`, `EXISTS`, `ON`, `REFERENCES`, `FROM`, `JOIN`, `UPDATE`, or `USING`, also for the few keywords that double
 * as functions (`LEFT(`, `REPLACE(`, `IF(`). Strings (`'...'` with `''`, prefixed `N'`, `X'`,
 * `B'`, `E'` with backslash escapes, and PostgreSQL `$tag$...$tag$`) are String, with `''` and
 * `E'` escapes as Constant. Quoted identifiers (`"..."`, backticks, SQL Server `[...]`) are
 * Variable, as are parameters (`$1`, `?`, `:name`, `@name`, `@@name`). Numbers are Number;
 * `--` and block comments are Comment; operators (`::` included) are Operator; `(`, `)`, `,`,
 * `;`, `.`, and subscript brackets are Punctuation. A name after `.` is never a keyword.
 *
 * States: 0 in code; otherwise the low 3 bits hold the open construct ([BLOCK_COMMENT], a
 * [QUOTED] string, an [ESCAPED] `E'` string, a [DOUBLE_QUOTED] or [BACKTICK] identifier, or a
 * [DOLLAR] string), which all continue across lines as in SQL. A dollar string keeps a 24-bit
 * [LexerDelimiter] hash of its tag in bits 3-26, so `$a$` only ends at `$a$`.
 *
 * Known gaps: block comments do not nest (PostgreSQL nests them); MySQL `#` comments are plain;
 * a `[` after a name is a subscript, so `SELECT x[1]` and T-SQL `[name]` right after a word differ.
 */
internal object SqlLexer : HighlightLexer() {
    private const val MODE_MASK = 7
    private const val BLOCK_COMMENT = 1
    private const val QUOTED = 2
    private const val ESCAPED = 3
    private const val DOUBLE_QUOTED = 4
    private const val BACKTICK = 5
    private const val DOLLAR = 6
    private const val TAG_SHIFT = 3
    private const val TAG_BITS = 24

    // Word kinds.
    private const val KEYWORD = 1
    private const val TYPE = 2
    private const val CONSTANT = 3
    private const val FUNCTION = 4
    private const val CALLABLE = 8
    private const val NAMES_TABLE = 16

    private val words = LexerWords(caseInsensitive = true)

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var index = start
        if (state != 0) {
            val close = continueConstruct(text, start, end, state, out)
            if (close < 0) return state
            index = close
        }
        var afterDot = false
        var valueBefore = false
        var tableNext = false
        while (index < end && !out.stopped) {
            val char = text[index]
            val next = if (index + 1 < end) text[index + 1] else ' '
            val wasAfterDot = afterDot
            afterDot = false
            val hadValue = valueBefore
            valueBefore = false
            val wasTableNext = tableNext
            if (char != ' ' && char != '\t') tableNext = false
            when {
                char == ' ' || char == '\t' -> {
                    index += 1
                    afterDot = wasAfterDot
                }
                char == '-' && next == '-' -> {
                    out.span(index, end, RoleCode.COMMENT)
                    index = end
                }
                char == '/' && next == '*' -> {
                    val close = LexerScan.blockCommentEnd(text, index + 2, end)
                    out.span(index, if (close < 0) end else close, RoleCode.COMMENT)
                    if (close < 0) return BLOCK_COMMENT
                    index = close
                }
                char == '\'' -> {
                    val result = open(text, index, index, end, QUOTED, out)
                    if (result < 0) return QUOTED
                    index = result
                    valueBefore = true
                }
                (char == 'E' || char == 'e') && next == '\'' -> {
                    val result = open(text, index, index + 1, end, ESCAPED, out)
                    if (result < 0) return ESCAPED
                    index = result
                    valueBefore = true
                }
                isStringPrefix(char) && next == '\'' -> {
                    val result = open(text, index, index + 1, end, QUOTED, out)
                    if (result < 0) return QUOTED
                    index = result
                    valueBefore = true
                }
                char == '"' || char == '`' -> {
                    val close = identifierEnd(text, index + 1, end, char)
                    out.span(index, if (close < 0) end else close, RoleCode.VARIABLE)
                    if (close < 0) return if (char == '"') DOUBLE_QUOTED else BACKTICK
                    index = close
                    valueBefore = true
                }
                char == '[' -> {
                    var close = index + 1
                    while (close < end && text[close] != ']' && text[close] != '[') close += 1
                    if (!hadValue && close < end && text[close] == ']') {
                        out.span(index, close + 1, RoleCode.VARIABLE)
                        index = close + 1
                        valueBefore = true
                    } else {
                        out.span(index, index + 1, RoleCode.PUNCTUATION)
                        index += 1
                    }
                }
                char == '$' -> {
                    val result = dollar(text, index, end, out)
                    if (result < 0) return (-result - 1) shl TAG_SHIFT or DOLLAR
                    index = result
                    valueBefore = true
                }
                char == '?' -> {
                    var after = index + 1
                    while (after < end && isDigit(text[after])) after += 1
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueBefore = true
                }
                char == ':' && next == ':' -> {
                    out.span(index, index + 2, RoleCode.OPERATOR)
                    index += 2
                }
                (char == ':' || char == '@') && (isNameStart(next) || (char == '@' && next == '@')) -> {
                    var after = index + 1
                    while (after < end && text[after] == '@') after += 1
                    while (after < end && isNamePart(text[after])) after += 1
                    out.span(index, after, RoleCode.VARIABLE)
                    index = after
                    valueBefore = true
                }
                isDigit(char) || (char == '.' && isDigit(next)) -> {
                    val after = numberEnd(text, index, end)
                    out.span(index, after, RoleCode.NUMBER)
                    index = after
                    valueBefore = true
                }
                isNameStart(char) -> {
                    var after = index + 1
                    while (after < end && isNamePart(text[after])) after += 1
                    // The role decides how a following `[` reads, so the state-only pass needs it too.
                    val role = nameRole(text, index, after, end, wasAfterDot, wasTableNext)
                    tableNext = !wasAfterDot && words.lookup(text, index, after) and NAMES_TABLE != 0
                    if (role != 0) {
                        out.span(index, after, role)
                    } else if (!out.collecting) {
                        out.span(index, after, RoleCode.VARIABLE)
                    }
                    valueBefore = role != RoleCode.KEYWORD
                    index = after
                }
                char == '.' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    afterDot = true
                }
                char == ']' || char == ')' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                    valueBefore = true
                }
                char == '(' || char == ',' || char == ';' -> {
                    out.span(index, index + 1, RoleCode.PUNCTUATION)
                    index += 1
                }
                isOperator(char) -> {
                    var after = index + 1
                    while (after < end && after < index + 3 && isOperator(text[after]) && !(text[after] == '-' && after + 1 < end && text[after + 1] == '-')) after += 1
                    out.span(index, after, RoleCode.OPERATOR)
                    index = after
                }
                else -> index += 1
            }
        }
        return 0
    }

    private fun nameRole(
        text: CharArray,
        from: Int,
        to: Int,
        end: Int,
        afterDot: Boolean,
        tableName: Boolean,
    ): Int {
        // `INSERT INTO t (a, b)`: a table before its column list is not a call.
        val call = !tableName && LexerScan.skipBlanks(text, to, end).let { it < end && text[it] == '(' }
        if (afterDot) return if (call) RoleCode.FUNCTION else 0
        val kind = words.lookup(text, from, to)
        return when {
            kind == 0 -> if (call) RoleCode.FUNCTION else 0
            kind and CALLABLE != 0 && call -> RoleCode.FUNCTION
            else ->
                when (kind and 7) {
                    KEYWORD -> RoleCode.KEYWORD
                    TYPE -> RoleCode.TYPE
                    CONSTANT -> RoleCode.CONSTANT
                    else -> RoleCode.FUNCTION
                }
        }
    }

    /**
     * Opens a `'` string whose prefix starts at [prefix] and quote is at [quote]; returns the
     * offset after it, or -1 when it continues past the line.
     */
    private fun open(
        text: CharArray,
        prefix: Int,
        quote: Int,
        end: Int,
        mode: Int,
        out: SpanSink,
    ): Int {
        out.span(prefix, quote + 1, RoleCode.STRING)
        return continuedString(text, quote + 1, end, mode == ESCAPED, out)
    }

    /** Continues a construct open at the line start; returns the offset after it closes, or -1. */
    private fun continueConstruct(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int =
        when (state and MODE_MASK) {
            BLOCK_COMMENT -> {
                val close = LexerScan.blockCommentEnd(text, start, end)
                out.span(start, if (close < 0) end else close, RoleCode.COMMENT)
                close
            }
            DOUBLE_QUOTED, BACKTICK -> {
                val close = identifierEnd(text, start, end, if (state and MODE_MASK == DOUBLE_QUOTED) '"' else '`')
                out.span(start, if (close < 0) end else close, RoleCode.VARIABLE)
                close
            }
            DOLLAR -> {
                val tag = (state ushr TAG_SHIFT) and ((1 shl TAG_BITS) - 1)
                val close = dollarEnd(text, start, end, tag)
                out.span(start, if (close < 0) end else close, RoleCode.STRING)
                close
            }
            else -> continuedString(text, start, end, state and MODE_MASK == ESCAPED, out)
        }

    /** String text from [from], with `''` (and `\x` when [escaped]) as Constant; the offset after the close, or -1. */
    private fun continuedString(
        text: CharArray,
        start: Int,
        end: Int,
        escaped: Boolean,
        out: SpanSink,
    ): Int {
        var index = start
        var segment = start
        while (index < end) {
            val char = text[index]
            if (escaped && char == '\\') {
                val length = if (index + 1 < end) 2 else 1
                out.span(segment, index, RoleCode.STRING)
                out.span(index, index + length, RoleCode.CONSTANT)
                index += length
                segment = index
                continue
            }
            if (char == '\'') {
                if (index + 1 < end && text[index + 1] == '\'') {
                    out.span(segment, index, RoleCode.STRING)
                    out.span(index, index + 2, RoleCode.CONSTANT)
                    index += 2
                    segment = index
                    continue
                }
                out.span(segment, index + 1, RoleCode.STRING)
                return index + 1
            }
            index += 1
        }
        out.span(segment, end, RoleCode.STRING)
        return -1
    }

    /** The offset after a quoted identifier's close (a doubled quote stays inside), or -1. */
    private fun identifierEnd(
        text: CharArray,
        from: Int,
        end: Int,
        quote: Char,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] == quote) {
                if (index + 1 < end && text[index + 1] == quote) {
                    index += 2
                    continue
                }
                return index + 1
            }
            index += 1
        }
        return -1
    }

    /**
     * `$1` parameters and `$tag$` strings at [at]. Returns the offset after what it lexed, or,
     * when a dollar string continues past the line, `-(hash + 1)` of its tag.
     */
    private fun dollar(
        text: CharArray,
        at: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        var index = at + 1
        if (index < end && isDigit(text[index])) {
            while (index < end && isDigit(text[index])) index += 1
            out.span(at, index, RoleCode.VARIABLE)
            return index
        }
        while (index < end && (isAsciiLetter(text[index]) || isDigit(text[index]) || text[index] == '_')) index += 1
        if (index >= end || text[index] != '$' || (index > at + 1 && isDigit(text[at + 1]))) return at + 1
        val tag = LexerDelimiter.of(text, at + 1, index, TAG_BITS)
        val close = dollarEnd(text, index + 1, end, tag)
        out.span(at, if (close < 0) end else close, RoleCode.STRING)
        return if (close < 0) -(tag + 1) else close
    }

    /** The offset after the `$tag$` with hash [tag] that closes a dollar string, or -1. */
    private fun dollarEnd(
        text: CharArray,
        from: Int,
        end: Int,
        tag: Int,
    ): Int {
        var index = from
        while (index < end) {
            if (text[index] != '$') {
                index += 1
                continue
            }
            var after = index + 1
            while (after < end && (isAsciiLetter(text[after]) || isDigit(text[after]) || text[after] == '_')) after += 1
            if (after < end && text[after] == '$' && LexerDelimiter.of(text, index + 1, after, TAG_BITS) == tag) return after + 1
            // A `$` that ends a different tag may start the right one.
            index = after
        }
        return -1
    }

    private fun numberEnd(
        text: CharArray,
        at: Int,
        end: Int,
    ): Int {
        var index = at
        if (text[index] == '0' && index + 1 < end && (text[index + 1] == 'x' || text[index + 1] == 'X')) {
            index += 2
            while (index < end && isHexDigit(text[index])) index += 1
            return index
        }
        while (index < end && (isDigit(text[index]) || text[index] == '_')) index += 1
        if (index < end && text[index] == '.' && !(index + 1 < end && text[index + 1] == '.')) {
            index += 1
            while (index < end && isDigit(text[index])) index += 1
        }
        if (index < end && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent += 1
            if (exponent < end && isDigit(text[exponent])) {
                index = exponent
                while (index < end && isDigit(text[index])) index += 1
            }
        }
        return index
    }

    private fun isStringPrefix(char: Char): Boolean = char == 'N' || char == 'n' || char == 'X' || char == 'x' || char == 'B' || char == 'b'

    private fun isNameStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || (char.code >= 0x80 && char.isLetter())

    private fun isNamePart(char: Char): Boolean = isNameStart(char) || isDigit(char) || char == '$'

    private fun isOperator(char: Char): Boolean =
        when (char) {
            '=', '<', '>', '!', '+', '-', '*', '/', '%', '|', '&', '^', '~', '#' -> true
            else -> false
        }

    init {
        words.register(
            KEYWORD,
            "select", "where", "and", "or", "not", "in", "is", "like", "ilike", "between", "as",
            "inner", "full", "outer", "cross", "natural", "group", "by", "order", "having", "limit", "offset", "fetch",
            "first", "next", "rows", "row", "only", "union", "all", "intersect", "except", "distinct", "values",
            "set", "delete", "merge", "matched", "then", "when", "else", "end", "case", "create", "alter", "drop",
            "truncate", "view", "index", "unique", "primary", "key", "foreign", "constraint", "check",
            "default", "column", "add", "rename", "to", "database", "schema", "temporary", "temp", "with", "recursive",
            "returning", "asc", "desc", "nulls", "cascade", "restrict", "grant", "revoke", "begin", "commit", "rollback",
            "transaction", "savepoint", "function", "procedure", "trigger", "returns", "return", "language", "declare", "after",
            "before", "instead", "for", "each", "execute", "exec", "call", "do", "over", "partition", "window", "range",
            "preceding", "following", "unbounded", "current", "lateral", "any", "some", "cast", "collate", "escape", "similar",
            "auto_increment", "autoincrement", "identity", "generated", "always", "stored", "virtual", "explain", "analyze",
            "vacuum", "use", "show", "describe", "pragma", "lock", "conflict", "nothing", "top", "pivot", "unpivot", "loop",
            "while", "elsif", "exception", "raise", "notice", "perform", "owner", "role", "comment", "ignore", "duplicate",
            "engine", "materialized", "refresh", "concurrently", "sequence", "type", "enum", "domain", "extension", "policy",
            "security", "definer", "invoker", "volatile", "stable", "immutable", "strict", "of", "out", "inout", "variadic",
            "setof", "tablespace", "without", "zone", "go", "open", "close", "cursor", "deallocate", "prepare",
            "filter", "within", "ordinal", "tablesample", "qualify", "local", "global", "session",
            "isolation", "level", "read", "write", "committed", "uncommitted", "repeatable", "serializable", "work", "release",
            "grants", "privileges", "public", "cluster", "reindex", "attach", "detach", "upsert", "abort", "plan", "query",
        )
        words.register(KEYWORD or NAMES_TABLE, "into", "table", "exists", "on", "references", "from", "join", "update", "using")
        words.register(KEYWORD or CALLABLE, "left", "right", "replace", "if", "insert")
        words.register(
            TYPE,
            "int", "integer", "smallint", "bigint", "tinyint", "mediumint", "serial", "bigserial", "smallserial", "decimal",
            "numeric", "real", "float", "double", "precision", "money", "char", "character", "varchar", "nchar", "nvarchar",
            "varying", "text", "tinytext", "mediumtext", "longtext", "clob", "blob", "bytea", "binary", "varbinary", "boolean",
            "bool", "bit", "date", "time", "timestamp", "timestamptz", "datetime", "datetime2", "datetimeoffset", "interval",
            "year", "uuid", "json", "jsonb", "xml", "array", "inet", "cidr", "macaddr", "point", "geometry", "geography",
            "int2", "int4", "int8", "float4", "float8", "uniqueidentifier", "smalldatetime", "image", "ntext", "number",
            "varchar2", "nvarchar2", "tsvector", "tsquery", "oid", "regclass", "hstore", "citext", "unsigned", "signed",
        )
        words.register(CONSTANT, "true", "false", "null", "unknown")
        words.register(
            FUNCTION,
            "current_date", "current_time", "current_timestamp", "current_user", "session_user", "localtime", "localtimestamp",
        )
    }
}
