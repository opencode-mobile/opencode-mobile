package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import dev.opencode.mobile.theme.LetterAccent

sealed class StartDecorator {
    data object None : StartDecorator()

    data class Letter(
        val character: Char,
        val accent: LetterAccent,
        val statusDot: OpenCodeListItemStatusTone? = null,
        /** Small worktree mark on the top-end corner. The status dot stays bottom-end. */
        val worktree: Boolean = false,
    ) : StartDecorator()

    data class Icon(
        val imageVector: ImageVector,
        val contentDescription: String?,
    ) : StartDecorator()

    data class IconCircle(
        val imageVector: ImageVector,
        val contentDescription: String?,
    ) : StartDecorator()
}

internal fun normalizedBadgeLetter(character: Char): String {
    val upper = character.uppercaseChar()
    return if (upper.isLetterOrDigit()) upper.toString() else "?"
}
