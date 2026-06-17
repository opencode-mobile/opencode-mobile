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
    fun shouldRender_simpleInlineMarkdownAsNativeText() {
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("Use `inline code` here."))
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("Read [the docs](https://example.com)."))
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("This is **important**."))
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("First paragraph.\n\nSecond paragraph."))
        assertFalse(OpenCodeRenderedMarkdownDetector.shouldRender("Plain text stays native."))
    }
}
