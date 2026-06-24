package dev.opencode.mobile.markdown

import kotlinx.serialization.Serializable

@Serializable
data class TextMateGrammarManifest(
    val schemaVersion: Int,
    val generator: String,
    val shikiVersion: String,
    val languages: List<TextMateGrammarManifestEntry>,
) {
    private val languageEntries: Map<String, TextMateGrammarManifestEntry> =
        languages.associateBy { it.language }
    private val aliasEntries: Map<String, TextMateGrammarManifestEntry> =
        languages
            .flatMap { entry -> entry.aliases.map { alias -> alias to entry } }
            .toMap()

    fun entryFor(languageOrAlias: String?): TextMateGrammarManifestEntry? {
        val normalized = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return languageEntries[normalized] ?: aliasEntries[normalized]
    }

    fun contains(languageOrAlias: String?): Boolean = entryFor(languageOrAlias) != null
}

@Serializable
data class TextMateGrammarManifestEntry(
    val language: String,
    val displayName: String,
    val aliases: List<String>,
    val grammarModule: String,
    val grammars: List<TextMateGrammarDescriptor>,
)

@Serializable
data class TextMateGrammarDescriptor(
    val name: String,
    val displayName: String,
    val scopeName: String,
    val resourcePath: String,
    val embeddedLanguages: List<String>,
    val aliases: List<String>,
)

object OpenCodeTextMateGrammarManifest {
    fun decode(json: String): TextMateGrammarManifest =
        OpenCodeMarkdownJson.json.decodeFromString<TextMateGrammarManifest>(json)
}

data class TextMateScopeToken(
    val start: Int,
    val end: Int,
    val scopes: List<String>,
)

object OpenCodeTextMateScopeMapper {
    fun roleFor(scopes: List<String>): CodeTokenRole {
        val normalizedScopes = scopes.asReversed().map { it.lowercase() }

        if (
            normalizedScopes.any { scope -> scope.startsWith("punctuation.definition.string.") } &&
            normalizedScopes.any { scope -> scope == "string.quoted" || scope.startsWith("string.quoted.") }
        ) {
            return CodeTokenRole.String
        }

        normalizedScopes.forEach { scope ->
            for ((role, matchers) in roleMatchers) {
                if (matchers.any { matcher -> matcher.matches(scope) }) {
                    return role
                }
            }
        }

        return CodeTokenRole.Plain
    }

    fun codeSpanFor(token: TextMateScopeToken): CodeSpan =
        CodeSpan(
            start = token.start,
            end = token.end,
            role = roleFor(token.scopes),
        )

    private val roleMatchers: List<Pair<CodeTokenRole, List<ScopeMatcher>>> =
        listOf(
            CodeTokenRole.Critical to listOf(ScopeMatcher.ExactPrefix("invalid")),
            CodeTokenRole.Inserted to listOf(ScopeMatcher.ExactPrefix("markup.inserted")),
            CodeTokenRole.Deleted to listOf(ScopeMatcher.ExactPrefix("markup.deleted")),
            CodeTokenRole.Warning to
                listOf(
                    ScopeMatcher.ExactPrefix("markup.changed"),
                    ScopeMatcher.ExactPrefix("meta.diff.header"),
                ),
            CodeTokenRole.Comment to listOf(ScopeMatcher.ExactPrefix("comment")),
            CodeTokenRole.String to listOf(ScopeMatcher.ExactPrefix("string")),
            CodeTokenRole.Number to listOf(ScopeMatcher.ExactPrefix("constant.numeric")),
            CodeTokenRole.Constant to
                listOf(
                    ScopeMatcher.ExactPrefix("constant.language"),
                    ScopeMatcher.ExactPrefix("constant.character"),
                    ScopeMatcher.ExactPrefix("constant.other"),
                ),
            CodeTokenRole.Operator to
                listOf(
                    ScopeMatcher.ExactPrefix("keyword.operator"),
                    ScopeMatcher.ExactPrefix("storage.type.operator"),
                ),
            CodeTokenRole.Keyword to
                listOf(
                    ScopeMatcher.ExactPrefix("keyword"),
                    ScopeMatcher.ExactPrefix("storage"),
                ),
            CodeTokenRole.Property to
                listOf(
                    ScopeMatcher.ExactPrefix("variable.other.property"),
                    ScopeMatcher.ExactPrefix("support.type.property-name"),
                    ScopeMatcher.ExactPrefix("meta.object-literal.key"),
                ),
            CodeTokenRole.Type to
                listOf(
                    ScopeMatcher.ExactPrefix("entity.name.type"),
                    ScopeMatcher.ExactPrefix("entity.name.class"),
                    ScopeMatcher.ExactPrefix("entity.name.struct"),
                    ScopeMatcher.ExactPrefix("support.type"),
                    ScopeMatcher.ExactPrefix("support.class"),
                ),
            CodeTokenRole.Function to
                listOf(
                    ScopeMatcher.ExactPrefix("entity.name.function"),
                    ScopeMatcher.ExactPrefix("support.function"),
                    ScopeMatcher.ExactPrefix("variable.function"),
                ),
            CodeTokenRole.Tag to listOf(ScopeMatcher.ExactPrefix("entity.name.tag")),
            CodeTokenRole.Attribute to listOf(ScopeMatcher.ExactPrefix("entity.other.attribute-name")),
            CodeTokenRole.Variable to listOf(ScopeMatcher.ExactPrefix("variable")),
            CodeTokenRole.Punctuation to
                listOf(
                    ScopeMatcher.ExactPrefix("punctuation"),
                    ScopeMatcher.ExactPrefix("meta.brace"),
                ),
        )

    private sealed interface ScopeMatcher {
        fun matches(scope: String): Boolean

        data class ExactPrefix(
            private val value: String,
        ) : ScopeMatcher {
            override fun matches(scope: String): Boolean = scope == value || scope.startsWith("$value.")
        }
    }
}
