package dev.opencode.mobile.markdown

internal expect fun bundledTestResourceReader(): OpenCodeTextMateResourceReader

internal fun bundledGrammarBundle(): TextMateGrammarBundle =
    OpenCodeTextMateGrammarBundleLoader
        .loadBundled(bundledTestResourceReader())
        .bundle ?: error("missing bundled TextMate grammar bundle")
