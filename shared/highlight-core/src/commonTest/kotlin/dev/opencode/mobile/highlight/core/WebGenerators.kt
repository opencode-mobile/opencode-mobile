package dev.opencode.mobile.highlight.core

/**
 * Random pieces for the web and markup languages (HTML, XML, CSS, PHP, Markdown), merged into
 * [HighlightGenerators]: tags, comments, strings, script and style elements, PHP islands, and
 * fences appear opened, closed, and damaged, so streaming and edits cross every embedded stretch.
 */
internal object WebGenerators {
    val languages = listOf("html", "xml", "css", "php", "markdown")

    private val markupPieces =
        listOf(
            "<", ">", "</", "/>", "<div", "</div>", "<p class=\"a\">", " id='x'", " hidden", "=", "\"", "'", "&amp;", "&#169;", "&nope",
            "<!--", "-->", "<!DOCTYPE html>", "<![CDATA[", "]]>", "<?xml version=\"1.0\"?>", "<?", "?>", " ", " ", "\n", "\n", "\r\n",
            "\t", "text", "😀", "\uD83D", "\uDE00", "​", "\r",
        )

    private val htmlPieces =
        listOf(
            "<script>", "</script>", "<script type=\"module\">", "<script lang=\"ts\">", "<script type=\"application/json\">",
            "<script type=\"text/x\">", "<style>", "</style>", "<SCRIPT>", "</SCRIPT", "`", "${'$'}{", "{", "}", "color: red;",
            "var x = 1;", "\"s", "/*", "*/", "//",
        )

    private val xmlPieces =
        listOf("<ns:a", " xmlns:ns=\"u\"", "<!DOCTYPE a [", "<!ENTITY e \"v\">", "]", "]>", "%p;", "<?pi x=\"1\"?>", "<a/>", "&e;")

    private val cssPieces =
        listOf(
            ".a", "#b", "a:hover", "::before", "[x=\"y\" i]", "{", "}", ":", ";", ",", "color", "margin", "--v", "${'$'}x", "#{", "@media",
            "screen and (min-width: 1px)", "@import", "@include m", "url(", ")", "(", "12px", "1.5em", "-2", "50%", "#fff", "!important",
            "\"s\"", "'open", "\\201C", "/* c */", "/* open", "*/", "// line", ">", "+", "~", "&", "*", " ", " ", "\n", "\n", "\r\n",
            "\t", "😀", "\uD83D", "\uDE00",
        )

    private val phpPieces =
        listOf(
            "<?php", "<?php\n", "<?=", "?>", "<? ", "${'$'}x", "${'$'}this->a", "->", "::", "\"s ${'$'}v {${'$'}a['k']} \\n\"", "\"open",
            "'s\\'q'", "'open", "<<<EOT\n", "<<<'EOT'\n", "<<<\"EOT\"", "\nEOT;", "\n  EOT", "EOT", "#", "# c", "// c", "/* c", "#[A]",
            "function", "class", "Foo", "foo(", "TRUE", "null", "PHP_EOL", "123", "0x1F", "(", ")", ";", "{${'$'}", "${'$'}{",
        )

    private val markdownPieces =
        listOf(
            "# ", "## ", "\n", "\n", "\n", "\n\n", "\r\n", "- ", "* ", "1. ", "> ", "[ ] ", "`", "``", "```", "````", "~~~", "```ts\n",
            "```json\n", "```markdown\n", "```html\n", "```php\n", "```python\n", "```unknown\n", "    ", "  ", "*", "**", "_", "__",
            "~~", "[a]", "(b)", "[a](b \"t\")", "![i](s)", "[r][id]", "[id]: u", "<https://x>", "<kbd>", "</kbd>", "<div>", "</div>",
            "<script>", "</script>", "<!--", "-->", "|", "|---|", "---", "***", "===", "+++", "\\*", "&amp;", "text", "word_word", "{",
            "}", "\"s\"", "let x = 1;", "def f():", "/*", "😀", "\uD83D", "\uDE00", " ", " ",
        )

    val pieces: Map<String, List<String>> =
        mapOf(
            "html" to markupPieces + htmlPieces,
            "xml" to markupPieces + xmlPieces,
            "css" to cssPieces,
            "php" to markupPieces + htmlPieces + phpPieces,
            "markdown" to markdownPieces,
        )
}
