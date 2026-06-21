package dev.opencode.mobile.markdown

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun interface OpenCodeTextMateResourceReader {
    fun readText(resourcePath: String): String?
}

data class TextMateGrammarPayload(
    val descriptor: TextMateGrammarDescriptor,
    val json: JsonObject,
    val grammar: TextMateGrammar,
) {
    val scopeName: String = grammar.scopeName
}

data class TextMateGrammarLoadDiagnostic(
    val kind: TextMateGrammarLoadDiagnosticKind,
    val resourcePath: String,
    val message: String,
)

enum class TextMateGrammarLoadDiagnosticKind {
    ManifestMissing,
    ManifestInvalid,
    GrammarMissing,
    GrammarInvalid,
    ScopeMismatch,
}

data class TextMateGrammarBundleLoadResult(
    val bundle: TextMateGrammarBundle?,
    val diagnostics: List<TextMateGrammarLoadDiagnostic>,
)

class TextMateGrammarBundle(
    val manifest: TextMateGrammarManifest,
    val grammars: List<TextMateGrammarPayload>,
    val diagnostics: List<TextMateGrammarLoadDiagnostic> = emptyList(),
) {
    private val grammarByResourcePath: Map<String, TextMateGrammarPayload> =
        grammars.associateBy { grammar -> grammar.descriptor.resourcePath }
    private val grammarByScopeName: Map<String, TextMateGrammarPayload> =
        grammars.associateBy { grammar -> grammar.scopeName }

    fun grammarsFor(languageOrAlias: String?): List<TextMateGrammarPayload> {
        val entry = manifest.entryFor(languageOrAlias) ?: return emptyList()
        return entry.grammars.mapNotNull { descriptor -> grammarByResourcePath[descriptor.resourcePath] }
    }

    fun primaryGrammarFor(languageOrAlias: String?): TextMateGrammarPayload? {
        val requested = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val entry = manifest.entryFor(requested) ?: return null
        val loaded = entry.grammars.mapNotNull { descriptor -> grammarByResourcePath[descriptor.resourcePath] }
        val preferredNames = setOf(entry.language.lowercase(), requested) + entry.aliases.map { alias -> alias.lowercase() }

        return loaded.firstOrNull { grammar ->
            grammar.descriptor.name.lowercase() in preferredNames ||
                grammar.descriptor.aliases.any { alias -> alias.lowercase() in preferredNames }
        } ?: loaded.firstOrNull()
    }

    fun grammarForScope(scopeName: String): TextMateGrammarPayload? = grammarByScopeName[scopeName]
}

object OpenCodeTextMateGrammarBundleLoader {
    const val defaultManifestResourcePath: String = "textmate-grammars/manifest.json"

    fun loadBundled(reader: OpenCodeTextMateResourceReader): TextMateGrammarBundleLoadResult {
        val manifestJson =
            reader.readText(defaultManifestResourcePath)
                ?: return TextMateGrammarBundleLoadResult(
                    bundle = null,
                    diagnostics =
                        listOf(
                            TextMateGrammarLoadDiagnostic(
                                kind = TextMateGrammarLoadDiagnosticKind.ManifestMissing,
                                resourcePath = defaultManifestResourcePath,
                                message = "Bundled TextMate grammar manifest was not found.",
                            ),
                        ),
                )

        return loadFromManifestJson(manifestJson, reader)
    }

    fun loadFromManifestJson(
        manifestJson: String,
        reader: OpenCodeTextMateResourceReader,
    ): TextMateGrammarBundleLoadResult {
        val manifest =
            try {
                OpenCodeTextMateGrammarManifest.decode(manifestJson)
            } catch (error: SerializationException) {
                return TextMateGrammarBundleLoadResult(
                    bundle = null,
                    diagnostics =
                        listOf(
                            TextMateGrammarLoadDiagnostic(
                                kind = TextMateGrammarLoadDiagnosticKind.ManifestInvalid,
                                resourcePath = defaultManifestResourcePath,
                                message = error.message ?: "Bundled TextMate grammar manifest could not be decoded.",
                            ),
                        ),
                )
            } catch (error: IllegalArgumentException) {
                return TextMateGrammarBundleLoadResult(
                    bundle = null,
                    diagnostics =
                        listOf(
                            TextMateGrammarLoadDiagnostic(
                                kind = TextMateGrammarLoadDiagnosticKind.ManifestInvalid,
                                resourcePath = defaultManifestResourcePath,
                                message = error.message ?: "Bundled TextMate grammar manifest could not be decoded.",
                            ),
                        ),
                )
            }

        return TextMateGrammarBundleLoadResult(
            bundle = load(manifest, reader),
            diagnostics = emptyList(),
        )
    }

    fun load(
        manifest: TextMateGrammarManifest,
        reader: OpenCodeTextMateResourceReader,
    ): TextMateGrammarBundle {
        val diagnostics = mutableListOf<TextMateGrammarLoadDiagnostic>()
        val descriptors =
            manifest.languages
                .flatMap { entry -> entry.grammars }
                .distinctBy { descriptor -> descriptor.resourcePath }
        val grammars =
            descriptors.mapNotNull { descriptor ->
                val grammarJson =
                    reader.readText(descriptor.resourcePath)
                        ?: run {
                            diagnostics +=
                                TextMateGrammarLoadDiagnostic(
                                    kind = TextMateGrammarLoadDiagnosticKind.GrammarMissing,
                                    resourcePath = descriptor.resourcePath,
                                    message = "Bundled grammar resource was not found for ${descriptor.scopeName}.",
                                )
                            return@mapNotNull null
                        }

                val grammarObject =
                    try {
                        OpenCodeMarkdownJson.json.parseToJsonElement(grammarJson).jsonObject
                    } catch (error: SerializationException) {
                        diagnostics +=
                            TextMateGrammarLoadDiagnostic(
                                kind = TextMateGrammarLoadDiagnosticKind.GrammarInvalid,
                                resourcePath = descriptor.resourcePath,
                                message = error.message ?: "Bundled grammar resource could not be decoded.",
                            )
                        return@mapNotNull null
                    } catch (error: IllegalArgumentException) {
                        diagnostics +=
                            TextMateGrammarLoadDiagnostic(
                                kind = TextMateGrammarLoadDiagnosticKind.GrammarInvalid,
                                resourcePath = descriptor.resourcePath,
                                message = error.message ?: "Bundled grammar resource could not be decoded.",
                            )
                        return@mapNotNull null
                    }

                val grammar =
                    OpenCodeTextMateGrammarDecoder.decodeOrNull(grammarObject)
                        ?: run {
                            diagnostics +=
                                TextMateGrammarLoadDiagnostic(
                                    kind = TextMateGrammarLoadDiagnosticKind.GrammarInvalid,
                                    resourcePath = descriptor.resourcePath,
                                    message = "Bundled grammar resource did not match the supported TextMate grammar schema.",
                                )
                            return@mapNotNull null
                        }

                val payload =
                    TextMateGrammarPayload(
                        descriptor = descriptor,
                        json = grammarObject,
                        grammar = grammar,
                    )
                if (payload.scopeName != descriptor.scopeName) {
                    diagnostics +=
                        TextMateGrammarLoadDiagnostic(
                            kind = TextMateGrammarLoadDiagnosticKind.ScopeMismatch,
                            resourcePath = descriptor.resourcePath,
                            message =
                                "Manifest scope ${descriptor.scopeName} did not match bundled grammar scope ${payload.scopeName}.",
                        )
                }
                payload
            }

        return TextMateGrammarBundle(
            manifest = manifest,
            grammars = grammars,
            diagnostics = diagnostics,
        )
    }
}
