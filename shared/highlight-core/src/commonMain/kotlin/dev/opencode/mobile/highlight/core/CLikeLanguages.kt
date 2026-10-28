package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.CLikeSyntax.Companion.CONTEXTUAL
import dev.opencode.mobile.highlight.core.CLikeSyntax.Companion.DECLARES_FUNCTION
import dev.opencode.mobile.highlight.core.CLikeSyntax.Companion.DECLARES_TYPE
import dev.opencode.mobile.highlight.core.CLikeSyntax.Companion.VALUE

/** The [CLikeLexer] configurations: one per language, built once and shared. */
internal object CLikeLanguages {
    private const val KEYWORD = RoleCode.KEYWORD
    private const val CONSTANT = RoleCode.CONSTANT
    private const val TYPE = RoleCode.TYPE

    private fun MutableMap<String, Int>.add(
        value: Int,
        words: String,
    ) {
        words.split(' ').forEach { put(it, value) }
    }

    private val javaScriptWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "break case catch const continue debugger default delete do else enum export extends finally for if import in " +
                    "instanceof let new return static switch throw try typeof var void while with yield await constructor",
            )
            add(KEYWORD or CONTEXTUAL, "async get set of from as")
            add(KEYWORD or VALUE, "this super")
            add(KEYWORD or DECLARES_FUNCTION, "function")
            add(KEYWORD or DECLARES_TYPE, "class")
            add(CONSTANT, "true false null undefined NaN Infinity")
        }

    private val typeScriptWords: Map<String, Int> =
        buildMap {
            putAll(javaScriptWords)
            add(KEYWORD, "implements private protected public")
            add(KEYWORD or DECLARES_TYPE, "interface enum")
            add(
                KEYWORD or CONTEXTUAL,
                "module declare abstract readonly override satisfies keyof infer is asserts unique accessor global",
            )
            add(KEYWORD or CONTEXTUAL or DECLARES_TYPE, "type namespace")
            add(TYPE, "string number boolean any unknown never object symbol bigint void")
        }

    private val kotlinWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "as break continue do else for if in is package return throw try typeof val var when while import catch " +
                    "finally constructor init",
            )
            add(
                KEYWORD or CONTEXTUAL,
                "by delegate dynamic field file get param property receiver set setparam where actual abstract annotation " +
                    "companion const crossinline data enum expect external final infix inline inner internal lateinit noinline " +
                    "open operator out override private protected public reified sealed suspend tailrec vararg value",
            )
            add(KEYWORD or VALUE, "this super")
            add(KEYWORD or DECLARES_FUNCTION, "fun")
            add(KEYWORD or DECLARES_TYPE, "class interface object typealias")
            add(CONSTANT, "true false null")
        }

    private val swiftWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "deinit fileprivate import init inout internal let operator private precedencegroup public rethrows static " +
                    "subscript var break case catch continue default defer do else fallthrough for guard if in repeat return " +
                    "throw switch where while as is try throws await async",
            )
            add(
                KEYWORD or CONTEXTUAL,
                "convenience dynamic didSet final get indirect lazy mutating nonmutating optional override postfix prefix " +
                    "infix required set unowned weak willSet nonisolated isolated some any consuming borrowing package open",
            )
            add(KEYWORD or VALUE, "self super")
            add(KEYWORD or DECLARES_FUNCTION, "func")
            add(KEYWORD or CONTEXTUAL or DECLARES_FUNCTION, "macro")
            add(KEYWORD or DECLARES_TYPE, "associatedtype class enum extension protocol struct typealias")
            add(KEYWORD or CONTEXTUAL or DECLARES_TYPE, "actor")
            add(TYPE, "Self")
            add(CONSTANT, "true false nil")
        }

    val javaScript =
        CLikeLexer(
            CLikeSyntax(
                words = javaScriptWords,
                identifierRole = RoleCode.VARIABLE,
                strings = CLikeSyntax.STRINGS_JS,
                nestedComments = false,
                regexLiterals = true,
                jsx = true,
                objectKeys = true,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_PRIVATE,
            ),
        )

    val typeScript =
        CLikeLexer(
            CLikeSyntax(
                words = typeScriptWords,
                identifierRole = RoleCode.VARIABLE,
                strings = CLikeSyntax.STRINGS_JS,
                nestedComments = false,
                regexLiterals = true,
                jsx = true,
                objectKeys = true,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_PRIVATE,
            ),
        )

    val kotlin =
        CLikeLexer(
            CLikeSyntax(
                words = kotlinWords,
                identifierRole = 0,
                strings = CLikeSyntax.STRINGS_KOTLIN,
                nestedComments = true,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_NONE,
            ),
        )

    val swift =
        CLikeLexer(
            CLikeSyntax(
                words = swiftWords,
                identifierRole = 0,
                strings = CLikeSyntax.STRINGS_SWIFT,
                nestedComments = true,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_SWIFT,
            ),
        )
}
