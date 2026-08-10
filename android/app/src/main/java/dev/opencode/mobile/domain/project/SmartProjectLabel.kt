package dev.opencode.mobile.domain.project

/**
 * Single display string for Session Chat project chrome.
 *
 * Unique basename among [peerWorktrees] → basename only.
 * Collision → shortest distinguishing parent chain as `..Parent[/…]/name`.
 */
fun smartProjectLabel(
    focusWorktree: String,
    peerWorktrees: Collection<String>,
): String {
    val focusParts = pathParts(focusWorktree)
    val name = focusParts.lastOrNull()?.takeIf { it.isNotBlank() } ?: focusWorktree.trim()
    if (name.isEmpty()) return focusWorktree

    val sameNamedPeers =
        peerWorktrees
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && normalizePath(it) != normalizePath(focusWorktree) }
            .map { pathParts(it) }
            .filter { it.lastOrNull() == name }
            .toList()

    if (sameNamedPeers.isEmpty()) return name

    val maxParentDepth = (focusParts.size - 1).coerceAtLeast(0)
    var depth = 1
    while (depth <= maxParentDepth) {
        val key = focusParts.takeLast(depth + 1)
        val conflict =
            sameNamedPeers.any { peer ->
                peer.size >= key.size && peer.takeLast(key.size) == key
            }
        if (!conflict) break
        depth += 1
    }
    depth = depth.coerceIn(1, maxParentDepth.coerceAtLeast(1))
    if (maxParentDepth == 0) return name

    val parents = focusParts.takeLast(depth + 1).dropLast(1)
    if (parents.isEmpty()) return name
    return ".." + parents.joinToString("/") + "/" + name
}

private fun normalizePath(path: String): String = path.trim().trimEnd('/')

private fun pathParts(path: String): List<String> =
    normalizePath(path)
        .split('/')
        .filter { it.isNotEmpty() }
