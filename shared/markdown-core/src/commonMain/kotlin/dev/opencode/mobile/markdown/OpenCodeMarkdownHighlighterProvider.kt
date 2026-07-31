package dev.opencode.mobile.markdown

internal expect fun defaultOpenCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter

internal expect fun createOpenCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter

internal object OpenCodeMarkdownHighlighterProvider {
    val default: OpenCodeCodeHighlighter
        get() = defaultOpenCodePlatformMarkdownHighlighter()
}
