import Foundation

struct ChangedFileGroup: Equatable, Identifiable {
    var id: String { directory }
    let directory: String
    let files: [FileDiff]
}

func changedFileName(_ path: String) -> String {
    guard let index = path.lastIndex(of: "/") else { return path }
    return String(path[path.index(after: index)...])
}

func groupChangedFiles(_ files: [FileDiff], prefixSource: [FileDiff]? = nil) -> [ChangedFileGroup] {
    guard !files.isEmpty else { return [] }
    let source: [FileDiff] = {
        guard let prefixSource, !prefixSource.isEmpty else { return files }
        return prefixSource
    }()
    let prefix = commonDirectoryPrefix(source.map(\.path))
    var grouped: [(directory: String, files: [FileDiff])] = []
    var indexByDirectory: [String: Int] = [:]
    for file in files {
        let relative = relativeDirectory(directoryOf(file.path), prefix: prefix)
        if let index = indexByDirectory[relative] {
            grouped[index].files.append(file)
        } else {
            indexByDirectory[relative] = grouped.count
            grouped.append((relative, [file]))
        }
    }
    if grouped.count == 1, grouped[0].directory == "." {
        let fullDir = directoryOf(files[0].path)
        return [ChangedFileGroup(directory: fullDir.isEmpty ? "." : fullDir, files: files)]
    }
    return grouped.map { ChangedFileGroup(directory: $0.directory, files: $0.files) }
}

private func directoryOf(_ path: String) -> String {
    guard let index = path.lastIndex(of: "/") else { return "" }
    return String(path[..<index])
}

private func commonDirectoryPrefix(_ paths: [String]) -> String {
    let dirs = paths.map { directoryOf($0).split(separator: "/").map(String.init).filter { !$0.isEmpty } }
    guard var count = dirs.first?.count else { return "" }
    let first = dirs[0]
    for dir in dirs.dropFirst() {
        let shared = min(count, dir.count)
        var index = 0
        while index < shared, dir[index] == first[index] {
            index += 1
        }
        count = index
        if count == 0 { return "" }
    }
    return first.prefix(count).joined(separator: "/")
}

private func relativeDirectory(_ dir: String, prefix: String) -> String {
    if prefix.isEmpty { return dir.isEmpty ? "." : dir }
    if dir == prefix { return "." }
    let prefixSlash = prefix + "/"
    if dir.hasPrefix(prefixSlash) {
        return String(dir.dropFirst(prefixSlash.count))
    }
    return dir.isEmpty ? "." : dir
}
