package dev.opencode.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRenderedMarkdownDetectorTest {
    @Test
    fun shouldRender_complexBlockMarkdown() {
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("# Heading"))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("- Item"))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("1. Item"))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("> quoted"))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("| A | B |\n|---|---|"))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("```kotlin\nval answer = 42\n```"))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("```diff\n+added\n-deleted\n```"))
    }

    @Test
    fun shouldRender_simpleInlineMarkdownWithNativeRenderer() {
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("Use `inline code` here."))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("Read [the docs](https://example.com)."))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("This is **important**."))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("See https://example.com/docs for details."))
        assertTrue(OpenCodeRenderedMarkdownDetector.shouldRender("Open <https://example.com>."))
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("First paragraph.\n\nSecond paragraph."))
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("Plain text stays native."))
    }
}
