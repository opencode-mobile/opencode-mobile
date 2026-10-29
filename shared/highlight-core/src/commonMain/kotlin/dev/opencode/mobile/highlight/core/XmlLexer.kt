package dev.opencode.mobile.highlight.core

/**
 * XML and its dialects (SVG, XSLT, plist, RSS): case-sensitive tags with namespace prefixes,
 * attributes, entities, comments, CDATA sections, processing instructions, and doctypes with an
 * internal subset; see [MarkupLexer].
 */
internal object XmlLexer : MarkupLexer(html = false)
