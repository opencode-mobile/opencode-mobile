package dev.opencode.mobile.markdown

internal expect fun openCodePlatformMarkdownHighlighter(): OpenCodeCodeHighlighter

internal object OpenCodeMarkdownHighlighterProvider {
    val default: OpenCodeCodeHighlighter
        get() = openCodePlatformMarkdownHighlighter()
}
