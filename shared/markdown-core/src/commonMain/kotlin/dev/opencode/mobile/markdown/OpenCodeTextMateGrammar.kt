package dev.opencode.mobile.markdown

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class TextMateGrammar(
    val name: String,
    val scopeName: String,
    val displayName: String? = null,
    val fileTypes: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val firstLineMatch: String? = null,
    val patterns: List<TextMatePattern> = emptyList(),
    val repository: Map<String, TextMatePattern> = emptyMap(),
    val injections: Map<String, TextMatePattern> = emptyMap(),
    val embeddedLangs: List<String> = emptyList(),
    val embeddedLangsLazy: List<String> = emptyList(),
)

@Serializable
data class TextMatePattern(
    val include: String? = null,
    val name: String? = null,
    val contentName: String? = null,
    val match: String? = null,
    val captures: Map<String, TextMateCapture> = emptyMap(),
    val begin: String? = null,
    val beginCaptures: Map<String, TextMateCapture> = emptyMap(),
    val end: String? = null,
    val endCaptures: Map<String, TextMateCapture> = emptyMap(),
    @SerialName("while")
    val whilePattern: String? = null,
    val whileCaptures: Map<String, TextMateCapture> = emptyMap(),
    val patterns: List<TextMatePattern> = emptyList(),
    val repository: Map<String, TextMatePattern> = emptyMap(),
    val applyEndPatternLast: JsonElement? = null,
) {
    val isIncludeOnly: Boolean =
        include != null &&
            match == null &&
            begin == null &&
            end == null &&
            whilePattern == null &&
            patterns.isEmpty()

    val shouldApplyEndPatternLast: Boolean =
        applyEndPatternLast?.jsonPrimitive?.booleanOrNull
            ?: (applyEndPatternLast?.jsonPrimitive?.intOrNull == 1)
}

@Serializable
data class TextMateCapture(
    val name: String? = null,
    val patterns: List<TextMatePattern> = emptyList(),
)

data class TextMateGrammarSummary(
    val patternCount: Int,
    val repositoryCount: Int,
    val includeCount: Int,
    val matchRuleCount: Int,
    val beginEndRuleCount: Int,
)

object OpenCodeTextMateGrammarDecoder {
    fun decode(json: JsonObject): TextMateGrammar =
        OpenCodeMarkdownJson.json.decodeFromJsonElement(TextMateGrammar.serializer(), json)

    fun decodeOrNull(json: JsonObject): TextMateGrammar? =
        try {
            decode(json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    fun summarize(grammar: TextMateGrammar): TextMateGrammarSummary {
        val patterns = mutableListOf<TextMatePattern>()

        fun collect(pattern: TextMatePattern) {
            patterns += pattern
            pattern.patterns.forEach(::collect)
            pattern.repository.values.forEach(::collect)
            pattern.captures.values.flatMap { it.patterns }.forEach(::collect)
            pattern.beginCaptures.values.flatMap { it.patterns }.forEach(::collect)
            pattern.endCaptures.values.flatMap { it.patterns }.forEach(::collect)
            pattern.whileCaptures.values.flatMap { it.patterns }.forEach(::collect)
        }

        grammar.patterns.forEach(::collect)
        grammar.repository.values.forEach(::collect)
        grammar.injections.values.forEach(::collect)

        return TextMateGrammarSummary(
            patternCount = patterns.size,
            repositoryCount = grammar.repository.size,
            includeCount = patterns.count { it.include != null },
            matchRuleCount = patterns.count { it.match != null },
            beginEndRuleCount = patterns.count { it.begin != null && it.end != null },
        )
    }
}
