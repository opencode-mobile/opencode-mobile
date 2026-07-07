package dev.opencode.mobile.markdown

/**
 * TextMate grammars occasionally use top-level alternation where one branch fails
 * Oniguruma/Joni compilation (for example shell `normal_statement` lookbehinds).
 * Decompose those patterns so each branch can compile independently.
 */
internal object OpenCodeTextMateRegexAlternationSupport {
    fun decompositionCandidates(pattern: String): List<String>? {
        val (prefix, alternation) = extractLeadingPrefixesAndAlternation(pattern) ?: return null
        val branches = splitTopLevelAlternationBranches(alternation) ?: return null
        return branches.map { branch -> prefix + branch }
    }

    private fun extractLeadingPrefixesAndAlternation(pattern: String): Pair<String, String>? {
        var index = 0
        val prefix = StringBuilder()
        while (index < pattern.length) {
            if (!pattern.startsWith("(?", index)) break
            when (pattern.getOrNull(index + 2)) {
                '!', '=' -> {
                    val end = findMatchingParen(pattern, index) ?: return null
                    prefix.append(pattern, index, end + 1)
                    index = end + 1
                }
                ':' -> {
                    val end = findMatchingParen(pattern, index) ?: return null
                    val group = pattern.substring(index, end + 1)
                    if (!group.startsWith("(?:")) return null
                    return prefix.toString() to group
                }
                else -> return null
            }
        }
        return null
    }

    private fun splitTopLevelAlternationBranches(group: String): List<String>? {
        if (!group.startsWith("(?:") || !group.endsWith(")")) return null
        val inner = group.substring(3, group.length - 1)
        val branches = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inClass = false
        var escaped = false
        for (ch in inner) {
            if (escaped) {
                current.append(ch)
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> {
                    escaped = true
                    current.append(ch)
                }
                '[' -> {
                    inClass = true
                    current.append(ch)
                }
                ']' -> {
                    inClass = false
                    current.append(ch)
                }
                '(' -> {
                    depth++
                    current.append(ch)
                }
                ')' -> {
                    depth--
                    current.append(ch)
                }
                '|' ->
                    if (depth == 0 && !inClass) {
                        branches += current.toString()
                        current.clear()
                    } else {
                        current.append(ch)
                    }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            branches += current.toString()
        }
        if (branches.size < 2) return null
        return branches
    }

    private fun findMatchingParen(
        text: String,
        openIndex: Int,
    ): Int? {
        if (text.getOrNull(openIndex) != '(') return null
        var depth = 0
        var inClass = false
        var escaped = false
        var index = openIndex
        while (index < text.length) {
            val ch = text[index]
            if (escaped) {
                escaped = false
            } else {
                when (ch) {
                    '\\' -> escaped = true
                    '[' -> inClass = true
                    ']' -> inClass = false
                    '(' -> if (!inClass) depth++
                    ')' ->
                        if (!inClass) {
                            depth--
                            if (depth == 0) return index
                        }
                }
            }
            index++
        }
        return null
    }
}
