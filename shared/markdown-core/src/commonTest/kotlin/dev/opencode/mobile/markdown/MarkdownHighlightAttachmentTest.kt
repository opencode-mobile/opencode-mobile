package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class MarkdownHighlightAttachmentTest {
    @Test
    fun fenceIdentityIsDeterministicAndAttachmentIsPure() {
        val document = codeDocument("first", "second")
        val revision = HighlightRevision(9)
        val first = document.codeFences("message-1", revision, policyEpoch = 3)
        val second = document.codeFences("message-1", revision, policyEpoch = 3)
        val fence = first.last()
        val result = resultFor(fence, listOf(CodeSpan(0, 6, CodeTokenRole.String)))

        val attachment = document.attachTextMateResult(fence, result)

        assertEquals(first, second)
        assertEquals(CodeBlockKey.deterministic("message-1", blockIndex = 1, fenceIndex = 1), fence.key)
        val attached = assertIs<MarkdownHighlightAttachment.Attached>(attachment).document
        assertNotSame(document, attached)
        assertEquals(emptyList(), (document.blocks[1] as MarkdownBlock.CodeBlock).spans)
        assertEquals(result.spans, (attached.blocks[1] as MarkdownBlock.CodeBlock).spans)
        assertEquals(document.sourceHash, attached.sourceHash)
        assertEquals(document.renderTreeVersion + 1, attached.renderTreeVersion)
    }

    @Test
    fun attachmentRejectsAResultForAnotherDocument() {
        val document = codeDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(1), policyEpoch = 0).single()

        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.DocumentChanged,
            document.copy(sourceHash = document.sourceHash + 1).attachTextMateResult(fence, resultFor(fence)),
        )
    }

    @Test
    fun attachmentRejectsAFenceThatIsNoLongerThere() {
        val document = codeDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(1), policyEpoch = 0).single()
        val withoutFence =
            document.copy(blocks = listOf(MarkdownBlock.Paragraph(inlines = listOf(MarkdownInline.Text("source")))))

        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.FenceMissing,
            withoutFence.attachTextMateResult(fence, resultFor(fence)),
        )
    }

    @Test
    fun attachmentRejectsAResultForAnotherFence() {
        val document = codeDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(1), policyEpoch = 0).single()

        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.IdentityChanged,
            document.attachTextMateResult(fence, resultFor(fence).copy(key = CodeBlockKey("message-2:0:0"))),
        )
    }

    @Test
    fun attachmentRejectsRevisionAndPolicyMismatch() {
        val document = codeDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(10), policyEpoch = 4).single()

        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.RevisionChanged,
            document.attachTextMateResult(fence, resultFor(fence).copy(revision = HighlightRevision(11))),
        )
        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.PolicyChanged,
            document.attachTextMateResult(fence, resultFor(fence).copy(policyEpoch = 5)),
        )
    }

    @Test
    fun attachmentRejectsExactSourceMismatch() {
        val document = codeDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(1), policyEpoch = 2).single()

        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.SourceChanged,
            document.attachTextMateResult(fence, resultFor(fence).copy(source = "stale")),
        )
        assertRejected(
            MarkdownHighlightAttachment.RejectionReason.SourceChanged,
            document.attachTextMateResult(fence.copy(source = "stale"), resultFor(fence).copy(source = "stale")),
        )
    }

    @Test
    fun attachmentRejectsFailedAndCancelledResults() {
        val document = codeDocument("source")
        val fence = document.codeFences("message-1", HighlightRevision(1), policyEpoch = 0).single()

        for (outcome in listOf(TextMateParseOutcome.Failed, TextMateParseOutcome.Cancelled)) {
            assertRejected(
                MarkdownHighlightAttachment.RejectionReason.ResultNotAttachable,
                document.attachTextMateResult(fence, resultFor(fence).copy(outcome = outcome)),
            )
        }
        assertIs<MarkdownHighlightAttachment.Attached>(
            document.attachTextMateResult(fence, resultFor(fence).copy(outcome = TextMateParseOutcome.Partial)),
        )
    }

    private fun assertRejected(
        reason: MarkdownHighlightAttachment.RejectionReason,
        attachment: MarkdownHighlightAttachment,
    ) {
        assertEquals(MarkdownHighlightAttachment.Rejected(reason), attachment)
    }

    private fun codeDocument(vararg sources: String): MarkdownDocument =
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
        spans: List<CodeSpan> = emptyList(),
    ): HighlightResult =
        HighlightResult(
            key = fence.key,
            revision = fence.revision,
            source = fence.source,
            canonicalLanguage = "kotlin",
            policyEpoch = fence.policyEpoch,
            spans = spans,
            outcome = TextMateParseOutcome.Highlighted,
            diagnostics = emptyList(),
        )
}
