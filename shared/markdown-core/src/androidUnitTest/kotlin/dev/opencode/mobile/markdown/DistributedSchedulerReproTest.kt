package dev.opencode.mobile.markdown

import kotlin.test.Test

class DistributedSchedulerReproTest {
    @Test
    fun reproParseStructure() {
        val markdown =
            DistributedSchedulerReproTest::class.java.classLoader!!
                .getResource("repro/distributed-task-scheduler.md")!!
                .readText()

        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend),
            )

        val doc = OpenCodeMarkdownParser.parse(markdown, highlighter)

        println("=== BLOCK STRUCTURE (${doc.blocks.size} blocks) ===")
        doc.blocks.forEachIndexed { i, block ->
            when (block) {
                is MarkdownBlock.Heading -> println("[$i] Heading(level=${block.level}) ${plainTextFromMarkdownInlines(block.inlines)}")
                is MarkdownBlock.Paragraph -> println("[$i] Paragraph: ${plainTextFromMarkdownInlines(block.inlines).take(60)}")
                is MarkdownBlock.CodeBlock -> println(
                    "[$i] CodeBlock(lang=${block.language}, lines=${block.lines.size}, spans=${block.spans.size}, codeLen=${block.code.length})",
                )
                is MarkdownBlock.OrderedList -> println("[$i] OrderedList(start=${block.start}, items=${block.items.size})")
                is MarkdownBlock.BulletList -> println("[$i] BulletList(items=${block.items.size})")
                is MarkdownBlock.DiffBlock -> println("[$i] DiffBlock(lines=${block.lines.size})")
                is MarkdownBlock.Table -> println("[$i] Table")
                is MarkdownBlock.BlockQuote -> println("[$i] BlockQuote")
                MarkdownBlock.ThematicBreak -> println("[$i] ThematicBreak")
            }
        }

        println("=== DIAGNOSTICS ===")
        doc.diagnostics.forEach { println(it) }

        val codeBlocks = doc.blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
        codeBlocks.forEach { cb ->
            val len = cb.code.length
            val bad =
                cb.spans.withIndex().filter { (idx, span) ->
                    span.start < 0 || span.end > len || span.start >= span.end ||
                        (idx > 0 && span.start < cb.spans[idx - 1].end)
                }
            println("=== CODE BLOCK span invariant violations: ${bad.size} ===")
            bad.take(20).forEach { (idx, span) -> println("  span[$idx]=$span (codeLen=$len)") }
            println("code utf16 len=${cb.code.length}, code first line='${cb.code.lineSequence().first()}'")
            println("code last line='${cb.code.lineSequence().last()}'")
        }

        codeBlocks.forEach { cb ->
            println("=== LARGEST SPANS ===")
            cb.spans
                .sortedByDescending { it.end - it.start }
                .take(12)
                .forEach { span ->
                    val text = cb.code.substring(span.start, span.end).replace("\n", "\\n")
                    val shown = if (text.length > 80) text.take(80) + "…" else text
                    println("  ${span.role} len=${span.end - span.start} [${span.start},${span.end}) '$shown'")
                }
        }

        println("=== iOS JSON ROUND TRIP PATH ===")
        val requestJson =
            OpenCodeMarkdownJson.json.encodeToString(
                MarkdownParseRequest(markdown = markdown, variant = "assistantMessage", isStreaming = false),
            )
        val responseJson = OpenCodeMarkdownJson.parseMarkdownJson(requestJson)
        println("responseJson length=${responseJson.length}")
        val reDecoded = OpenCodeMarkdownJson.json.decodeFromString<MarkdownDocument>(responseJson)
        println("reDecoded blocks=${reDecoded.blocks.size} types=${reDecoded.blocks.map { it::class.simpleName }}")
    }
}
