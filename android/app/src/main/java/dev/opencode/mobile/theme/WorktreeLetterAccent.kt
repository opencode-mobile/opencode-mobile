package dev.opencode.mobile.theme

fun letterAccentForWorktree(worktree: String): LetterAccent {
    val index = (worktree.hashCode() and Int.MAX_VALUE) % LetterAccent.entries.size
    return LetterAccent.entries[index]
}

fun letterCharacterForProject(displayName: String): Char = displayName.firstOrNull()?.uppercaseChar() ?: '?'
