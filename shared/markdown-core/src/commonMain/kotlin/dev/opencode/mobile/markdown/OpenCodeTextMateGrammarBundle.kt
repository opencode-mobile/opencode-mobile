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

class TextMateGrammarBundle internal constructor(
    val manifest: TextMateGrammarManifest,
    private val reader: OpenCodeTextMateResourceReader?,
    preloadedGrammars: Map<String, TextMateGrammarPayload> = emptyMap(),
    preloadedDiagnostics: List<TextMateGrammarLoadDiagnostic> = emptyList(),
) {
    private val loadedByResourcePath = LinkedHashMap<String, TextMateGrammarPayload>()
    private val failedResourcePaths = mutableSetOf<String>()
    private val loadDiagnostics = preloadedDiagnostics.toMutableList()

    init {
        loadedByResourcePath.putAll(preloadedGrammars)
    }

    private val descriptorsByResourcePath: Map<String, TextMateGrammarDescriptor> by lazy {
        manifest.languages
            .flatMap { entry -> entry.grammars }
            .associateBy { descriptor -> descriptor.resourcePath }
    }

    private val descriptorsByScopeName: Map<String, TextMateGrammarDescriptor> by lazy {
        manifest.languages
            .flatMap { entry -> entry.grammars }
            .associateBy { descriptor -> descriptor.scopeName }
    }

    val grammars: List<TextMateGrammarPayload>
        get() = loadedByResourcePath.values.toList()

    val diagnostics: List<TextMateGrammarLoadDiagnostic>
        get() = loadDiagnostics.toList()

    fun grammarsFor(languageOrAlias: String?): List<TextMateGrammarPayload> {
        val entry = manifest.entryFor(languageOrAlias) ?: return emptyList()
        return entry.grammars.mapNotNull { descriptor -> grammarForDescriptor(descriptor) }
    }

    fun primaryGrammarFor(languageOrAlias: String?): TextMateGrammarPayload? {
        val requested = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val entry = manifest.entryFor(requested) ?: return null
        val loaded = entry.grammars.mapNotNull { descriptor -> grammarForDescriptor(descriptor) }
        val preferredNames = setOf(entry.language.lowercase(), requested) + entry.aliases.map { alias -> alias.lowercase() }

        return loaded.firstOrNull { grammar ->
            grammar.descriptor.name.lowercase() in preferredNames ||
                grammar.descriptor.aliases.any { alias -> alias.lowercase() in preferredNames }
        } ?: loaded.firstOrNull()
    }

    fun grammarForScope(scopeName: String): TextMateGrammarPayload? =
        descriptorsByScopeName[scopeName]?.let { descriptor -> grammarForDescriptor(descriptor) }

    internal fun preloadAllFromReader() {
        descriptorsByResourcePath.values.forEach { descriptor -> grammarForDescriptor(descriptor) }
    }

    companion object {
        fun fromPreloaded(
            manifest: TextMateGrammarManifest,
            grammars: List<TextMateGrammarPayload>,
            diagnostics: List<TextMateGrammarLoadDiagnostic> = emptyList(),
        ): TextMateGrammarBundle =
            TextMateGrammarBundle(
                manifest = manifest,
                reader = null,
                preloadedGrammars = grammars.associateBy { grammar -> grammar.descriptor.resourcePath },
                preloadedDiagnostics = diagnostics,
            )
    }

    private fun grammarForDescriptor(descriptor: TextMateGrammarDescriptor): TextMateGrammarPayload? {
        loadedByResourcePath[descriptor.resourcePath]?.let { return it }
        if (descriptor.resourcePath in failedResourcePaths) return null

        val resourceReader = reader ?: return null

        val result = OpenCodeTextMateGrammarBundleLoader.loadGrammarPayload(descriptor, resourceReader)
        loadDiagnostics += result.diagnostics
        val payload = result.payload
        if (payload == null) {
            failedResourcePaths += descriptor.resourcePath
            return null
        }

        return loadedByResourcePath.getOrPut(descriptor.resourcePath) { payload }
    }
}

object OpenCodeTextMateGrammarBundleLoader {
    const val defaultManifestResourcePath: String = "textmate-grammars/manifest.json"

    data class GrammarPayloadLoadResult(
        val payload: TextMateGrammarPayload?,
        val diagnostics: List<TextMateGrammarLoadDiagnostic>,
    )

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
            bundle =
                TextMateGrammarBundle(
                    manifest = manifest,
                    reader = reader,
                ),
            diagnostics = emptyList(),
        )
    }

    fun load(
        manifest: TextMateGrammarManifest,
        reader: OpenCodeTextMateResourceReader,
    ): TextMateGrammarBundle {
        val bundle =
            TextMateGrammarBundle(
                manifest = manifest,
                reader = reader,
            )
        bundle.preloadAllFromReader()
        return bundle
    }

    internal fun loadGrammarPayload(
        descriptor: TextMateGrammarDescriptor,
        reader: OpenCodeTextMateResourceReader,
    ): GrammarPayloadLoadResult {
        val diagnostics = mutableListOf<TextMateGrammarLoadDiagnostic>()
        val grammarJson =
            reader.readText(descriptor.resourcePath)
                ?: run {
                    diagnostics +=
                        TextMateGrammarLoadDiagnostic(
                            kind = TextMateGrammarLoadDiagnosticKind.GrammarMissing,
                            resourcePath = descriptor.resourcePath,
                            message = "Bundled grammar resource was not found for ${descriptor.scopeName}.",
                        )
                    return GrammarPayloadLoadResult(payload = null, diagnostics = diagnostics)
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
                return GrammarPayloadLoadResult(payload = null, diagnostics = diagnostics)
            } catch (error: IllegalArgumentException) {
                diagnostics +=
                    TextMateGrammarLoadDiagnostic(
                        kind = TextMateGrammarLoadDiagnosticKind.GrammarInvalid,
                        resourcePath = descriptor.resourcePath,
                        message = error.message ?: "Bundled grammar resource could not be decoded.",
                    )
                return GrammarPayloadLoadResult(payload = null, diagnostics = diagnostics)
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
                    return GrammarPayloadLoadResult(payload = null, diagnostics = diagnostics)
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
        return GrammarPayloadLoadResult(payload = payload, diagnostics = diagnostics)
    }
}
