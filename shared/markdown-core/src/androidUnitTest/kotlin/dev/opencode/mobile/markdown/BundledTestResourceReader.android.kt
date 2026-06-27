package dev.opencode.mobile.markdown

private object AndroidBundledTestResourceAnchor

internal actual fun bundledTestResourceReader(): OpenCodeTextMateResourceReader =
    OpenCodeTextMateResourceReader { resourcePath ->
        AndroidBundledTestResourceAnchor::class.java.classLoader
            ?.getResource(resourcePath)
            ?.readText()
    }
