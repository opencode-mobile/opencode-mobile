package dev.opencode.mobile.domain.text

import java.util.Locale

object TitleCaseIdentifier {
    fun displayName(value: String): String =
        value
            .split('-', '_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                }
            }.ifEmpty { value }
}
