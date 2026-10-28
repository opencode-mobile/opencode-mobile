package dev.opencode.mobile.highlight.core

/**
 * HTML: tags, attributes, entities, comments, and the doctype, with `<style>` content lexed as
 * CSS and `<script>` content as JavaScript, TypeScript, or JSON by its `type`; see [MarkupLexer].
 */
internal object HtmlLexer : MarkupLexer(html = true)
