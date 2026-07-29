package dev.opencode.mobile.markdown

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

fun interface OpenCodeTextMateResourceReader {
    fun readText(resourcePath: String): String?
}

data class TextMateGrammarPayload(
    val descriptor: TextMateGrammarDescriptor,
    val grammar: TextMateGrammar,
    val program: TextMateGrammarProgram,
) {
    val scopeName: String = grammar.scopeName
    val grammarId: GrammarId
        get() = program.grammarId

    constructor(
        descriptor: TextMateGrammarDescriptor,
        grammar: TextMateGrammar,
        grammarId: GrammarId,
    ) : this(
        descriptor = descriptor,
        grammar = grammar,
        program = TextMateGrammarProgram.compile(grammar, grammarId),
    )
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
    private val defaultMetricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    private val cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
    preloadedGrammars: Map<String, TextMateGrammarPayload> = emptyMap(),
    preloadedDiagnostics: List<TextMateGrammarLoadDiagnostic> = emptyList(),
) : TextMateCacheTrimmable {
    private val loadedByResourcePath = LinkedHashMap<String, TextMateGrammarPayload>()
    private val failedResourcePaths = mutableSetOf<String>()
    private val loadDiagnostics = preloadedDiagnostics.toMutableList()
    private val grammarIdsByResourcePath: Map<String, GrammarId> =
        manifest.languages
            .flatMap { entry -> entry.grammars }
            .mapIndexed { index, descriptor -> descriptor.resourcePath to GrammarId(index) }
            .toMap()

    init {
        preloadedGrammars.forEach { (path, payload) ->
            loadedByResourcePath[path] = payload
            cacheCoordinator.promote(
                key = grammarCacheKey(path),
                category = TextMateWeightedCacheCoordinator.Category.GrammarProgram,
                estimatedBytes = payload.program.estimatedBytes,
            )
        }
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

    fun grammarsFor(
        languageOrAlias: String?,
        metricsSink: OpenCodeTextMateMetricsSink = defaultMetricsSink,
    ): List<TextMateGrammarPayload> {
        val entry = manifest.entryFor(languageOrAlias) ?: return emptyList()
        return entry.grammars.mapNotNull { descriptor -> grammarForDescriptor(descriptor, metricsSink) }
    }

    fun primaryGrammarFor(
        languageOrAlias: String?,
        metricsSink: OpenCodeTextMateMetricsSink = defaultMetricsSink,
    ): TextMateGrammarPayload? {
        val requested = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val entry = manifest.entryFor(requested) ?: return null
        val loaded = entry.grammars.mapNotNull { descriptor -> grammarForDescriptor(descriptor, metricsSink) }
        val preferredNames = setOf(entry.language.lowercase(), requested) + entry.aliases.map { alias -> alias.lowercase() }

        return loaded.firstOrNull { grammar ->
            grammar.descriptor.name.lowercase() in preferredNames ||
                grammar.descriptor.aliases.any { alias -> alias.lowercase() in preferredNames }
        } ?: loaded.firstOrNull()
    }

    fun grammarForScope(
        scopeName: String,
        metricsSink: OpenCodeTextMateMetricsSink = defaultMetricsSink,
    ): TextMateGrammarPayload? =
        descriptorsByScopeName[scopeName]?.let { descriptor -> grammarForDescriptor(descriptor, metricsSink) }

    internal fun preloadAllFromReader() {
        descriptorsByResourcePath.values.forEach { descriptor -> grammarForDescriptor(descriptor, defaultMetricsSink) }
    }

    override fun trimCaches(level: MemoryPressure) {
        when (level) {
            MemoryPressure.Normal,
            MemoryPressure.Moderate,
            -> Unit
            MemoryPressure.Critical -> {
                loadedByResourcePath.keys.toList().forEach { path ->
                    val key = grammarCacheKey(path)
                    if (!cacheCoordinator.contains(key) || cacheCoordinator.removeIfUnleased(key)) {
                        loadedByResourcePath.remove(path)
                    }
                }
            }
        }
    }

    companion object {
        fun fromPreloaded(
            manifest: TextMateGrammarManifest,
            grammars: List<TextMateGrammarPayload>,
            diagnostics: List<TextMateGrammarLoadDiagnostic> = emptyList(),
            metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
            cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
        ): TextMateGrammarBundle =
            TextMateGrammarBundle(
                manifest = manifest,
                reader = null,
                defaultMetricsSink = metricsSink,
                cacheCoordinator = cacheCoordinator,
                preloadedGrammars = grammars.associateBy { grammar -> grammar.descriptor.resourcePath },
                preloadedDiagnostics = diagnostics,
            )

        internal fun grammarCacheKey(resourcePath: String): String = "grammar:$resourcePath"
    }

    private fun grammarForDescriptor(
        descriptor: TextMateGrammarDescriptor,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateGrammarPayload? {
        loadedByResourcePath[descriptor.resourcePath]?.let { cached ->
            cacheCoordinator.touch(grammarCacheKey(descriptor.resourcePath))
            if (metricsSink.isEnabled) {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.GrammarLoad,
                        outcome = TextMateMetricOutcome.CacheHit,
                    ),
                )
            }
            return cached
        }
        if (descriptor.resourcePath in failedResourcePaths) return null

        val resourceReader = reader ?: return null

        val startedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
        val grammarId =
            grammarIdsByResourcePath[descriptor.resourcePath]
                ?: GrammarId(loadedByResourcePath.size)
        val result =
            OpenCodeTextMateGrammarBundleLoader.loadGrammarPayload(
                descriptor = descriptor,
                reader = resourceReader,
                grammarId = grammarId,
            )
        loadDiagnostics += result.diagnostics
        val payload = result.payload
        if (payload == null) {
            failedResourcePaths += descriptor.resourcePath
            startedAt?.let {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.GrammarLoad,
                        outcome = TextMateMetricOutcome.Failure,
                        durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                    ),
                )
            }
            return null
        }

        return loadedByResourcePath.getOrPut(descriptor.resourcePath) {
            cacheCoordinator.promote(
                key = grammarCacheKey(descriptor.resourcePath),
                category = TextMateWeightedCacheCoordinator.Category.GrammarProgram,
                estimatedBytes = payload.program.estimatedBytes,
            )
            payload
        }.also {
            startedAt?.let {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.GrammarLoad,
                        outcome = TextMateMetricOutcome.CacheMiss,
                        durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                    ),
                )
            }
        }
    }
}

object OpenCodeTextMateGrammarBundleLoader {
    const val defaultManifestResourcePath: String = "textmate-grammars/manifest.json"

    data class GrammarPayloadLoadResult(
        val payload: TextMateGrammarPayload?,
        val diagnostics: List<TextMateGrammarLoadDiagnostic>,
    )

    fun loadBundled(
        reader: OpenCodeTextMateResourceReader,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    ): TextMateGrammarBundleLoadResult {
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

        return loadFromManifestJson(manifestJson, reader, metricsSink)
    }

    fun loadFromManifestJson(
        manifestJson: String,
        reader: OpenCodeTextMateResourceReader,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
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
                    defaultMetricsSink = metricsSink,
                ),
            diagnostics = emptyList(),
        )
    }

    fun load(
        manifest: TextMateGrammarManifest,
        reader: OpenCodeTextMateResourceReader,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    ): TextMateGrammarBundle {
        val bundle =
            TextMateGrammarBundle(
                manifest = manifest,
                reader = reader,
                defaultMetricsSink = metricsSink,
            )
        bundle.preloadAllFromReader()
        return bundle
    }

    internal fun loadGrammarPayload(
        descriptor: TextMateGrammarDescriptor,
        reader: OpenCodeTextMateResourceReader,
        grammarId: GrammarId = GrammarId(0),
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

        // Raw JSON is dropped after typed compile; only the immutable program is retained.
        val payload =
            TextMateGrammarPayload(
                descriptor = descriptor,
                grammar = grammar,
                grammarId = grammarId,
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
