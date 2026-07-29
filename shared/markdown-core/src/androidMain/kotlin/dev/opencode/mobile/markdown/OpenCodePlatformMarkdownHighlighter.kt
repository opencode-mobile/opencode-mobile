package dev.opencode.mobile.markdown

internal actual fun defaultOpenCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter =
    OpenCodeBundledJoniMarkdownHighlighter.default()

internal actual fun createOpenCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter =
    OpenCodeBundledJoniMarkdownHighlighter.createFromApplicationContext()
