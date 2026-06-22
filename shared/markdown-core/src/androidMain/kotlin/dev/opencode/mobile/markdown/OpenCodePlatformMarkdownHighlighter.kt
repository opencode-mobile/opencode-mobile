package dev.opencode.mobile.markdown

internal actual fun openCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter =
    OpenCodeBundledJoniMarkdownHighlighter.default()
