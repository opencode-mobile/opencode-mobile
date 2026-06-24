package dev.opencode.mobile.markdown

import kotlinx.serialization.json.buildJsonObject

/** A resource-free grammar bundle for common tests, including Kotlin/Native. */
fun testJavascriptGrammarBundle(): TextMateGrammarBundle {
    val grammar =
        TextMateGrammar(
            name = "JavaScript",
            scopeName = "source.js",
            patterns =
                listOf(
                    TextMatePattern(
                        name = "variable.other.js",
                        match = "\\b(?:withCache|client|options|maxSize|Map)\\b",
                    ),
                ),
        )
    val descriptor =
        TextMateGrammarDescriptor(
            name = "javascript",
            displayName = "JavaScript",
            scopeName = grammar.scopeName,
            resourcePath = "test/javascript.json",
            embeddedLanguages = emptyList(),
            aliases = listOf("js"),
        )
    return TextMateGrammarBundle.fromPreloaded(
        manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = "javascript",
                            displayName = "JavaScript",
                            aliases = listOf("js"),
                            grammarModule = "test",
                            grammars = listOf(descriptor),
                        ),
                    ),
            ),
        grammars = listOf(TextMateGrammarPayload(descriptor, buildJsonObject { }, grammar)),
    )
}
