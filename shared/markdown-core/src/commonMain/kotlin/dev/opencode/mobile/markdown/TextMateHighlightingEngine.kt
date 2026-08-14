package dev.opencode.mobile.markdown

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * The only caller-facing TextMate seam.
 *
 * Callers own publication, revisions, and stale-result rejection. Implementations
 * own the highlighter and all mutable state behind it.
 */
interface TextMateHighlightingEngine {
    suspend fun highlight(request: HighlightRequest): HighlightResult

    suspend fun trim(level: MemoryPressure) = Unit

    fun schedulerSnapshot(): TextMateSchedulerSnapshot? = null
}

/**
 * Small JSON adapter for Swift/Objective-C interop. The adapter owns exactly one
 * KMP engine instance; Swift supplies the serial asynchronous executor.
 */
class OpenCodeTextMateHighlightingEngineJson private constructor(
    private val engine: OpenCodeTextMateHighlightingEngine,
) {
    fun highlightJson(requestJson: String): String {
        val request = OpenCodeMarkdownJson.json.decodeFromString<HighlightRequest>(requestJson)
        return OpenCodeMarkdownJson.json.encodeToString(engine.highlightNow(request))
    }

    suspend fun trim(level: MemoryPressure) {
        engine.trim(level)
    }

    companion object {
        fun createDefault(): OpenCodeTextMateHighlightingEngineJson =
            OpenCodeTextMateHighlightingEngineJson(
                OpenCodeTextMateHighlightingEngine(createOpenCodePlatformMarkdownHighlighter()),
            )
    }
}

@Serializable
data class CodeBlockKey(
    val value: String,
) {
    companion object {
        fun deterministic(
            ownerKey: String,
            blockIndex: Int,
            fenceIndex: Int,
        ): CodeBlockKey =
            CodeBlockKey(
                value = "${ownerKey.length}:$ownerKey:$blockIndex:$fenceIndex",
            )
    }
}

@Serializable
data class HighlightRevision(
    val value: Long,
) {
    init {
        require(value >= 0) { "Highlight revisions must be non-negative." }
    }
}

@Serializable
enum class HighlightIntent {
    InteractiveFinal,
    VisibleHistory,
    BackgroundHistory,
}

@Serializable
enum class MemoryPressure {
    Normal,
    Moderate,
    Critical,
}

@Serializable
data class HighlightRequest(
    val key: CodeBlockKey,
    val revision: HighlightRevision,
    val language: String? = null,
    val source: String,
    val intent: HighlightIntent,
    val policyEpoch: Long,
    val messageCharacters: Int = source.length,
    val fenceIndex: Int = 0,
    val fenceCount: Int = 1,
)

@Serializable
data class HighlightResult(
    val key: CodeBlockKey,
    val revision: HighlightRevision,
    val source: String,
    val canonicalLanguage: String?,
    val policyEpoch: Long,
    val spans: List<CodeSpan>,
    val outcome: TextMateParseOutcome,
    val diagnostics: List<MarkdownDiagnostic>,
    val observation: TextMateParseObservation,
)

/**
 * Synchronous internals behind an asynchronous contract. Platform adapters choose
 * and enforce the single owner/executor on which this instance is invoked.
 */
class OpenCodeTextMateHighlightingEngine(
    private val highlighter: OpenCodeCodeHighlighter,
    private val cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
    private val incrementalStore: TextMateIncrementalStateStore =
        TextMateIncrementalStateStore(cacheCoordinator),
    private val suffixConvergenceEnabled: Boolean = true,
) : TextMateHighlightingEngine {
    private val latestRevisionByKey = mutableMapOf<String, Long>()

    /**
     * When true, observations include per-call RegexSearch / NativeRegexSearch
     * stage events. Default false — those stages dominate wall time on dense
     * grammars and are only needed for calibration drill-down.
     */
    @Volatile
    var recordPerSearchMetrics: Boolean = false

    override suspend fun highlight(request: HighlightRequest): HighlightResult = highlightNow(request)

    override suspend fun trim(level: MemoryPressure) {
        trimNow(level)
    }

    fun trimNow(level: MemoryPressure) {
        incrementalStore.trim(level)
        cacheCoordinator.trim(level)
        when (highlighter) {
            is OpenCodeSyntaxHighlighter -> highlighter.trimCaches(level)
            is TextMateCacheTrimmable -> highlighter.trimCaches(level)
            else -> Unit
        }
    }

    fun highlightNow(
        request: HighlightRequest,
        shouldCancel: () -> Boolean = { false },
    ): HighlightResult {
        if (shouldCancel()) {
            return emptyResult(
                request = request,
                outcome = TextMateParseOutcome.Cancelled,
                queueWaitNanoseconds = 0,
                schedulingReason = TextMateSchedulingReason.Supersession.name,
            )
        }
        val metrics = OpenCodeTextMateMetricsAccumulator(recordPerSearch = recordPerSearchMetrics)
        val startedAt = TimeSource.Monotonic.markNow()
        val previousRevision = latestRevisionByKey[request.key.value]
        latestRevisionByKey[request.key.value] = maxOf(previousRevision ?: -1L, request.revision.value)
        return try {
            val supported = highlighter.supportsLanguage(request.language)
            val session =
                if (highlighter is OpenCodeSyntaxHighlighter) {
                    TextMateIncrementalHighlightSession(
                        identity =
                            IncrementalBlockIdentity(
                                key = request.key,
                                canonicalLanguage = request.language.orEmpty(),
                                grammarResourcePath = "",
                                grammarId = GrammarId(-1),
                                policyEpoch = request.policyEpoch,
                            ),
                        intent = request.intent,
                        revision = request.revision,
                        previous = incrementalStore.get(request.key),
                        suffixConvergenceEnabled = suffixConvergenceEnabled,
                    )
                } else {
                    null
                }
            val highlighted =
                if (highlighter is OpenCodeSyntaxHighlighter && session != null) {
                    highlighter.highlight(
                        languageOrAlias = request.language,
                        code = request.source,
                        metricsSink = metrics,
                        shouldCancel = shouldCancel,
                        incremental = session,
                    )
                } else {
                    highlighter.highlight(
                        languageOrAlias = request.language,
                        code = request.source,
                        metricsSink = metrics,
                        shouldCancel = shouldCancel,
                    )
                }
            if (shouldCancel()) {
                return emptyResult(
                    request = request,
                    outcome = TextMateParseOutcome.Cancelled,
                    queueWaitNanoseconds = 0,
                    schedulingReason = TextMateSchedulingReason.Supersession.name,
                    cancelRequestedToStopNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                )
            }
            val stillLatest = latestRevisionByKey[request.key.value] == request.revision.value
            if (stillLatest) {
                session?.produced?.let(incrementalStore::promote)
            }
            val diagnostics =
                buildList {
                    if (request.language != null && !supported) {
                        add(
                            MarkdownDiagnostic(
                                kind = MarkdownDiagnosticKind.UnsupportedLanguage,
                                message = "No bundled grammar is registered for '${request.language}'.",
                            ),
                        )
                    }
                    addAll(highlighted.diagnostics)
                }.distinct()
            val outcome =
                highlighted.textMateOutcome
                    ?: if (highlighted.source == SyntaxHighlightSource.TextMate) {
                        TextMateParseOutcome.Highlighted
                    } else {
                        TextMateParseOutcome.Fallback
                    }
            result(
                request = request,
                canonicalLanguage = highlighted.canonicalLanguage,
                spans = highlighted.spans,
                outcome = outcome,
                diagnostics = diagnostics,
                metrics = metrics,
                totalNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                reusedLineCount = session?.reusedLineCount ?: 0,
                retokenizedLineCount = session?.retokenizedLineCount ?: 0,
                suffixReusedLineCount = session?.suffixReusedLineCount ?: 0,
                incrementalMode = session?.mode?.name,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            result(
                request = request,
                canonicalLanguage = null,
                spans = emptyList(),
                outcome = TextMateParseOutcome.Failed,
                diagnostics =
                    listOf(
                        MarkdownDiagnostic(
                            kind = MarkdownDiagnosticKind.HighlighterFallback,
                            message = error.message ?: "TextMate highlighting failed; semantic fallback was preserved.",
                        ),
                    ),
                metrics = metrics,
                totalNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
            )
        }
    }

    private fun result(
        request: HighlightRequest,
        canonicalLanguage: String?,
        spans: List<CodeSpan>,
        outcome: TextMateParseOutcome,
        diagnostics: List<MarkdownDiagnostic>,
        metrics: OpenCodeTextMateMetricsAccumulator,
        totalNanoseconds: Long,
        reusedLineCount: Int = 0,
        retokenizedLineCount: Int = 0,
        suffixReusedLineCount: Int = 0,
        incrementalMode: String? = null,
    ): HighlightResult =
        HighlightResult(
            key = request.key,
            revision = request.revision,
            source = request.source,
            canonicalLanguage = canonicalLanguage,
            policyEpoch = request.policyEpoch,
            spans = spans,
            outcome = outcome,
            diagnostics = diagnostics,
            observation =
                TextMateParseObservation(
                    requestedLanguage = request.language,
                    canonicalLanguage = canonicalLanguage,
                    outcome = outcome,
                    messageCharacters = request.messageCharacters,
                    fenceCharacters = request.source.length,
                    fenceLines = request.source.lineCount(),
                    fenceIndex = request.fenceIndex,
                    fenceCount = request.fenceCount,
                    spanCount = spans.size,
                    backend = metrics.backendName(),
                    totalNanoseconds = totalNanoseconds,
                    stages = metrics.stageSummaries(),
                    diagnosticKinds = diagnostics.map(MarkdownDiagnostic::kind).distinct(),
                    reusedLineCount = reusedLineCount,
                    retokenizedLineCount = retokenizedLineCount,
                    suffixReusedLineCount = suffixReusedLineCount,
                    incrementalMode = incrementalMode,
                ),
        )

    private fun String.lineCount(): Int = if (isEmpty()) 0 else count { character -> character == '\n' } + 1
}

data class MarkdownCodeFence(
    val locator: MarkdownFenceLocator,
    val key: CodeBlockKey,
    val revision: HighlightRevision,
    val language: String?,
    val source: String,
    val policyEpoch: Long,
) {
    fun request(
        intent: HighlightIntent,
        messageCharacters: Int,
        fenceCount: Int,
    ): HighlightRequest =
        HighlightRequest(
            key = key,
            revision = revision,
            language = language,
            source = source,
            intent = intent,
            policyEpoch = policyEpoch,
            messageCharacters = messageCharacters,
            fenceIndex = locator.fenceIndex,
            fenceCount = fenceCount,
        )
}

data class MarkdownFenceLocator(
    val documentSourceHash: Int,
    val blockIndex: Int,
    val fenceIndex: Int,
)

fun MarkdownDocument.codeFences(
    ownerKey: String,
    revision: HighlightRevision,
    policyEpoch: Long,
): List<MarkdownCodeFence> =
    buildList {
        blocks.forEachIndexed { blockIndex, block ->
            if (block is MarkdownBlock.CodeBlock) {
                val fenceIndex = size
                add(
                    MarkdownCodeFence(
                        locator =
                            MarkdownFenceLocator(
                                documentSourceHash = sourceHash,
                                blockIndex = blockIndex,
                                fenceIndex = fenceIndex,
                            ),
                        key = CodeBlockKey.deterministic(ownerKey, blockIndex, fenceIndex),
                        revision = revision,
                        language = block.language,
                        source = block.code,
                        policyEpoch = policyEpoch,
                    ),
                )
            }
        }
    }

sealed interface MarkdownHighlightAttachment {
    data class Attached(
        val document: MarkdownDocument,
    ) : MarkdownHighlightAttachment

    data class Rejected(
        val reason: RejectionReason,
    ) : MarkdownHighlightAttachment

    enum class RejectionReason {
        DocumentChanged,
        FenceMissing,
        IdentityChanged,
        RevisionChanged,
        PolicyChanged,
        SourceChanged,
        ResultNotAttachable,
    }
}

/**
 * Pure attachment. It never mutates [MarkdownDocument] and only replaces spans
 * after every caller-owned identity field and the exact fence source match.
 */
fun MarkdownDocument.attachTextMateResult(
    fence: MarkdownCodeFence,
    result: HighlightResult,
): MarkdownHighlightAttachment {
    if (sourceHash != fence.locator.documentSourceHash) {
        return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.DocumentChanged)
    }
    val block = blocks.getOrNull(fence.locator.blockIndex) as? MarkdownBlock.CodeBlock
        ?: return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.FenceMissing)
    if (result.key != fence.key) {
        return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.IdentityChanged)
    }
    if (result.revision != fence.revision) {
        return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.RevisionChanged)
    }
    if (result.policyEpoch != fence.policyEpoch) {
        return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.PolicyChanged)
    }
    if (result.source != fence.source || block.code != fence.source) {
        return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.SourceChanged)
    }
    if (result.outcome in
        setOf(
            TextMateParseOutcome.Failed,
            TextMateParseOutcome.Cancelled,
            TextMateParseOutcome.BudgetExceeded,
        )
    ) {
        return MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.ResultNotAttachable)
    }

    val updatedBlocks = blocks.toMutableList()
    updatedBlocks[fence.locator.blockIndex] = block.copy(spans = result.spans)
    val retainedDiagnostics =
        diagnostics.filterNot { diagnostic ->
            diagnostic.kind in
                setOf(
                    MarkdownDiagnosticKind.UnsupportedLanguage,
                    MarkdownDiagnosticKind.HighlighterFallback,
                    MarkdownDiagnosticKind.HighlighterBackendFallback,
                )
        }
    return MarkdownHighlightAttachment.Attached(
        copy(
            // Keep sourceHash stable for fence identity; bump paint identity so
            // platform UI equality notices span upgrades.
            renderTreeVersion = renderTreeVersion + 1,
            blocks = updatedBlocks,
            diagnostics = (retainedDiagnostics + result.diagnostics).distinct(),
        ),
    )
}
