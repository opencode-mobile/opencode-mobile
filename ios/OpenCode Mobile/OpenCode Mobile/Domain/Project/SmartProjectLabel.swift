import Foundation

/// Single display string for Session Chat project chrome.
///
/// Unique basename among `peerWorktrees` → basename only.
/// Collision → shortest distinguishing parent chain as `..Parent[/…]/name`.
func smartProjectLabel(focusWorktree: String, peerWorktrees: [String]) -> String {
    let focusParts = pathParts(focusWorktree)
    let name = focusParts.last.flatMap { $0.isEmpty ? nil : $0 } ?? focusWorktree.trimmingCharacters(in: .whitespacesAndNewlines)
    if name.isEmpty { return focusWorktree }

    let focusNormalized = normalizePath(focusWorktree)
    let sameNamedPeers: [[String]] = peerWorktrees
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty && normalizePath($0) != focusNormalized }
        .map(pathParts)
        .filter { $0.last == name }

    if sameNamedPeers.isEmpty { return name }

    let maxParentDepth = max(focusParts.count - 1, 0)
    var depth = 1
    while depth <= maxParentDepth {
        let key = Array(focusParts.suffix(depth + 1))
        let conflict = sameNamedPeers.contains { peer in
            peer.count >= key.count && Array(peer.suffix(key.count)) == key
        }
        if !conflict { break }
        depth += 1
    }
    depth = min(max(depth, 1), max(maxParentDepth, 1))
    if maxParentDepth == 0 { return name }

    let slice = Array(focusParts.suffix(depth + 1))
    let parents = Array(slice.dropLast())
    if parents.isEmpty { return name }
    return ".." + parents.joined(separator: "/") + "/" + name
}

private func normalizePath(_ path: String) -> String {
    var value = path.trimmingCharacters(in: .whitespacesAndNewlines)
    while value.hasSuffix("/") { value.removeLast() }
    return value
}

private func pathParts(_ path: String) -> [String] {
    normalizePath(path)
        .split(separator: "/", omittingEmptySubsequences: true)
        .map(String.init)
}
