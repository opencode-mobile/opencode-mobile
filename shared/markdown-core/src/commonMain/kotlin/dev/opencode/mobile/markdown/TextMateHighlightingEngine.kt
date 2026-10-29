package dev.opencode.mobile.markdown

import kotlinx.serialization.Serializable

/**
 * The caller-facing seam for highlighting one code fence at a time; [HighlightCoreHighlightingEngine]
 * is the implementation.
 *
 * Callers own publication, revisions, and stale-result rejection. Implementations
 * own the highlighter and all mutable state behind it.
 */
interface TextMateHighlightingEngine {
    suspend fun highlight(request: HighlightRequest): HighlightResult

    suspend fun trim(level: MemoryPressure) = Unit
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
)

@Serializable
enum class TextMateParseOutcome {
    Highlighted,
    NoSpans,
    /** Deadline passed: spans cover a prefix, the rest stays plain, and the next call continues. */
    Partial,
    Unsupported,
    Failed,
    Cancelled,
}

data class MarkdownCodeFence(
    val locator: MarkdownFenceLocator,
    val key: CodeBlockKey,
    val revision: HighlightRevision,
    val language: String?,
    val source: String,
    val policyEpoch: Long,
) {
    fun request(intent: HighlightIntent): HighlightRequest =
        HighlightRequest(
            key = key,
            revision = revision,
            language = language,
            source = source,
            intent = intent,
            policyEpoch = policyEpoch,
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
    if (result.outcome == TextMateParseOutcome.Failed || result.outcome == TextMateParseOutcome.Cancelled) {
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
