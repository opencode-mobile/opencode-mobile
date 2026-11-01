package dev.opencode.mobile.highlight.core

/**
 * The semantic role of a highlighted range; colors stay in each app's design system. Names match
 * markdown-core's `CodeTokenRole` so adapters map one to one by name.
 *
 * [code] is what [HighlightSpans] stores. Codes are stable: new roles are only ever appended.
 */
enum class HighlightRole {
    Plain,
    Comment,
    Keyword,
    String,
    Number,
    Type,
    Function,
    Property,
    Variable,
    Constant,
    Operator,
    Punctuation,
    Tag,
    Attribute,
    Inserted,
    Deleted,
    Warning,
    Critical,
    ;

    /** The integer stored in [HighlightSpans]. */
    val code: Int
        get() = ordinal

    companion object {
        private val byCode = entries.toTypedArray()

        /** The role for a [HighlightSpans] role code; [Plain] for a code this version does not know. */
        fun ofCode(code: Int): HighlightRole = if (code in byCode.indices) byCode[code] else Plain
    }
}

/** Role codes as constants for lexers, so the hot path never touches enum objects. */
internal object RoleCode {
    const val COMMENT = 1
    const val KEYWORD = 2
    const val STRING = 3
    const val NUMBER = 4
    const val TYPE = 5
    const val FUNCTION = 6
    const val PROPERTY = 7
    const val VARIABLE = 8
    const val CONSTANT = 9
    const val OPERATOR = 10
    const val PUNCTUATION = 11
    const val TAG = 12
    const val ATTRIBUTE = 13
    const val INSERTED = 14
    const val DELETED = 15
    const val WARNING = 16
    const val CRITICAL = 17
}
