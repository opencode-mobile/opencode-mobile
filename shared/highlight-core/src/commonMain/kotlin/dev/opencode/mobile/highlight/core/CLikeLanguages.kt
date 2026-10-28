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

    private val rustWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "as async await break const continue crate dyn else extern for if impl in let loop match mod move mut pub ref " +
                    "return static unsafe use where while yield",
            )
            add(KEYWORD or CONTEXTUAL, "union")
            add(KEYWORD or VALUE, "super")
            add(RoleCode.VARIABLE or VALUE, "self")
            add(KEYWORD or DECLARES_FUNCTION, "fn")
            add(KEYWORD or DECLARES_TYPE, "struct enum trait type")
            add(TYPE, "Self bool char str u8 u16 u32 u64 u128 usize i8 i16 i32 i64 i128 isize f32 f64")
            add(CONSTANT, "true false")
        }

    private val goWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "break case chan const continue default defer else fallthrough for go goto if import interface map range return " +
                    "select struct switch var",
            )
            add(KEYWORD or DECLARES_FUNCTION, "func")
            add(KEYWORD or DECLARES_TYPE, "type package")
            // Predeclared types read as keywords, as Shiki colors them.
            add(
                KEYWORD,
                "any bool byte comparable complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint " +
                    "uint8 uint16 uint32 uint64 uintptr",
            )
            add(CONSTANT, "true false nil iota")
        }

    private val javaWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "abstract assert break case catch const continue default do else extends final finally for goto if implements " +
                    "import instanceof native new package private protected public return static strictfp switch synchronized " +
                    "throw throws transient try volatile while boolean byte char short int long float double void",
            )
            add(KEYWORD or CONTEXTUAL, "var yield sealed permits")
            add(KEYWORD or VALUE, "this super")
            add(KEYWORD or DECLARES_TYPE, "class interface enum")
            add(KEYWORD or CONTEXTUAL or DECLARES_TYPE, "record")
            add(CONSTANT, "true false null")
        }

    private val cWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "auto break case const continue default do else extern for goto if inline register restrict return signed sizeof " +
                    "static switch typedef unsigned volatile while _Alignas _Alignof _Atomic _Bool _Complex _Generic _Imaginary " +
                    "_Noreturn _Static_assert _Thread_local alignas alignof constexpr static_assert thread_local typeof defined " +
                    "char double float int long short void bool",
            )
            add(KEYWORD or DECLARES_TYPE, "struct union enum")
            add(TYPE, "FILE")
            add(CONSTANT, "true false NULL nullptr")
        }

    private val cppWords: Map<String, Int> =
        buildMap {
            putAll(cWords)
            add(
                KEYWORD,
                "and and_eq asm bitand bitor catch compl concept consteval constinit const_cast co_await co_return co_yield " +
                    "decltype delete dynamic_cast explicit export friend mutable namespace new noexcept not not_eq operator or " +
                    "or_eq private protected public reinterpret_cast requires static_cast template throw try typeid using " +
                    "virtual xor xor_eq wchar_t char8_t char16_t char32_t",
            )
            add(KEYWORD, "override final")
            add(KEYWORD or CONTEXTUAL, "import module")
            add(KEYWORD or VALUE, "this")
            add(KEYWORD or DECLARES_TYPE, "class typename")
        }

    private val cSharpWords: Map<String, Int> =
        buildMap {
            add(
                KEYWORD,
                "abstract as break case catch checked const continue default delegate do else event explicit extern finally fixed " +
                    "for foreach goto if implicit in internal is lock namespace new operator out override params private protected " +
                    "public readonly ref return sealed sizeof stackalloc static switch throw try typeof unchecked unsafe using " +
                    "virtual volatile while bool byte char decimal double float int long object sbyte short string uint ulong " +
                    "ushort void",
            )
            add(
                KEYWORD or CONTEXTUAL,
                "add alias ascending async await by descending dynamic equals file from global group into join let managed " +
                    "nameof nint not notnull nuint on or orderby partial remove required scoped select unmanaged value var when " +
                    "where with yield and",
            )
            // Accessors, as in `{ get; init; }`.
            add(KEYWORD, "get set init")
            add(KEYWORD or VALUE, "this base")
            add(KEYWORD or DECLARES_TYPE, "class interface struct enum")
            add(KEYWORD or CONTEXTUAL or DECLARES_TYPE, "record")
            add(CONSTANT, "true false null")
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

    val rust =
        CLikeLexer(
            CLikeSyntax(
                words = rustWords,
                identifierRole = RoleCode.VARIABLE,
                strings = CLikeSyntax.STRINGS_RUST,
                nestedComments = true,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_RUST,
                allCapsConstants = true,
            ),
        )

    val go =
        CLikeLexer(
            CLikeSyntax(
                words = goWords,
                identifierRole = RoleCode.VARIABLE,
                strings = CLikeSyntax.STRINGS_GO,
                nestedComments = false,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_NONE,
            ),
        )

    val java =
        CLikeLexer(
            CLikeSyntax(
                words = javaWords,
                identifierRole = RoleCode.VARIABLE,
                strings = CLikeSyntax.STRINGS_JAVA,
                nestedComments = false,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_NONE,
            ),
        )

    val c =
        CLikeLexer(
            CLikeSyntax(
                words = cWords,
                identifierRole = 0,
                strings = CLikeSyntax.STRINGS_C,
                nestedComments = false,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_DIRECTIVE,
                allCapsConstants = true,
            ),
        )

    val cpp =
        CLikeLexer(
            CLikeSyntax(
                words = cppWords,
                identifierRole = 0,
                strings = CLikeSyntax.STRINGS_CPP,
                nestedComments = false,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_DIRECTIVE,
                allCapsConstants = true,
            ),
        )

    val cSharp =
        CLikeLexer(
            CLikeSyntax(
                words = cSharpWords,
                identifierRole = RoleCode.VARIABLE,
                strings = CLikeSyntax.STRINGS_CSHARP,
                nestedComments = false,
                regexLiterals = false,
                jsx = false,
                objectKeys = false,
                capitalizedTypes = true,
                hash = CLikeSyntax.HASH_DIRECTIVE,
            ),
        )
}
