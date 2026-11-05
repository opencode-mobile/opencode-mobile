package dev.opencode.mobile.theme

fun letterAccentForWorktree(worktree: String): LetterAccent {
    val hash = worktree.encodeToByteArray().fold(5381L) { acc, b -> (acc shl 5) + acc + b }
    val index = (hash and Int.MAX_VALUE.toLong()).toInt() % LetterAccent.entries.size
    return LetterAccent.entries[index]
}

fun letterCharacterForProject(displayName: String): Char = displayName.firstOrNull()?.uppercaseChar() ?: '?'
