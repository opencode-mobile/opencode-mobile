package dev.opencode.mobile.markdown

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class TextMateHighlightingEngineTest {
    @Test
    fun engineEchoesCallerIdentityAndMeasuresOneFence() {
        val engine =
            OpenCodeTextMateHighlightingEngine(
                object : OpenCodeCodeHighlighter {
                    override fun supportsLanguage(languageOrAlias: String?): Boolean = true

                    override fun highlight(
                        languageOrAlias: String?,
                        code: String,
                    ): SyntaxHighlightResult =
                        SyntaxHighlightResult(
                            spans = listOf(CodeSpan(0, code.length, CodeTokenRole.Keyword)),
                            diagnostics = emptyList(),
                            source = SyntaxHighlightSource.TextMate,
                            canonicalLanguage = "TypeScript",
                        )
                },
            )
        val request =
            HighlightRequest(
                key = CodeBlockKey("message:fence"),
                revision = HighlightRevision(41),
                language = "ts",
                source = "const answer = 42",
                intent = HighlightIntent.InteractiveFinal,
                policyEpoch = 7,
                messageCharacters = 30,
                fenceIndex = 1,
                fenceCount = 2,
            )

        val result = runSuspend { engine.highlight(request) }

        assertEquals(request.key, result.key)
        assertEquals(request.revision, result.revision)
        assertEquals(request.source, result.source)
        assertEquals(request.policyEpoch, result.policyEpoch)
        assertEquals(TextMateParseOutcome.Highlighted, result.outcome)
        assertEquals(1, result.observation.fenceIndex)
        assertEquals(2, result.observation.fenceCount)
        assertEquals(30, result.observation.messageCharacters)
    }

    @Test
    fun fenceLocatorIsDeterministicAndAttachmentIsPure() {
        val document = fallbackDocument("first", "second")
        val revision = HighlightRevision(9)
        val first = document.codeFences("message-1", revision, policyEpoch = 3)
        val second = document.codeFences("message-1", revision, policyEpoch = 3)
        val fence = first.last()
        val result = resultFor(fence, listOf(CodeSpan(0, 6, CodeTokenRole.String)))

        val attachment = document.attachTextMateResult(fence, result)

        assertEquals(first, second)
        val attached = assertIs<MarkdownHighlightAttachment.Attached>(attachment).document
        assertNotSame(document, attached)
        assertEquals(emptyList(), (document.blocks[1] as MarkdownBlock.CodeBlock).spans)
        assertEquals(result.spans, (attached.blocks[1] as MarkdownBlock.CodeBlock).spans)
    }

    @Test
    fun attachmentRejectsExactSourceMismatch() {
        val document = fallbackDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(1), policyEpoch = 2).single()
        val stale = resultFor(fence, emptyList()).copy(source = "stale")

        val attachment = document.attachTextMateResult(fence, stale)

        assertEquals(
            MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.SourceChanged),
            attachment,
        )
    }

    @Test
    fun attachmentRejectsRevisionAndPolicyMismatch() {
        val document = fallbackDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(10), policyEpoch = 4).single()

        assertEquals(
            MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.RevisionChanged),
            document.attachTextMateResult(
                fence,
                resultFor(fence, emptyList()).copy(revision = HighlightRevision(11)),
            ),
        )
        assertEquals(
            MarkdownHighlightAttachment.Rejected(MarkdownHighlightAttachment.RejectionReason.PolicyChanged),
            document.attachTextMateResult(
                fence,
                resultFor(fence, emptyList()).copy(policyEpoch = 5),
            ),
        )
    }

    private fun fallbackDocument(vararg sources: String): MarkdownDocument =
        MarkdownDocument(
            sourceHash = 19,
            blocks =
                sources.map { source ->
                    MarkdownBlock.CodeBlock(
                        language = "kotlin",
                        code = source,
                        lines = source.lines(),
                        spans = emptyList(),
                    )
                },
        )

    private fun resultFor(
        fence: MarkdownCodeFence,
        spans: List<CodeSpan>,
    ): HighlightResult =
        HighlightResult(
            key = fence.key,
            revision = fence.revision,
            source = fence.source,
            canonicalLanguage = "Kotlin",
            policyEpoch = fence.policyEpoch,
            spans = spans,
            outcome = TextMateParseOutcome.Highlighted,
            diagnostics = emptyList(),
            observation =
                TextMateParseObservation(
                    outcome = TextMateParseOutcome.Highlighted,
                    messageCharacters = fence.source.length,
                    fenceCharacters = fence.source.length,
                    fenceLines = 1,
                    fenceIndex = fence.locator.fenceIndex,
                    fenceCount = 1,
                    spanCount = spans.size,
                    totalNanoseconds = 1,
                    stages = emptyList(),
                    diagnosticKinds = emptyList(),
                ),
        )

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return requireNotNull(outcome).getOrThrow()
    }
}
