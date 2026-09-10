package dev.opencode.mobile.ui.changes

import dev.opencode.mobile.domain.session.FileDiff

data class ChangedFileGroup(
    val directory: String,
    val files: List<FileDiff>,
)

fun changedFileName(path: String): String {
    val index = path.lastIndexOf('/')
    return if (index < 0) path else path.substring(index + 1)
}

fun groupChangedFiles(
    files: List<FileDiff>,
    prefixSource: List<FileDiff> = files,
): List<ChangedFileGroup> {
    if (files.isEmpty()) return emptyList()
    val prefix = commonDirectoryPrefix((prefixSource.ifEmpty { files }).map(FileDiff::path))
    val grouped = LinkedHashMap<String, MutableList<FileDiff>>()
    for (file in files) {
        val relative = relativeDirectory(directoryOf(file.path), prefix)
        grouped.getOrPut(relative) { mutableListOf() }.add(file)
    }
    if (grouped.size == 1 && grouped.keys.first() == ".") {
        val fullDir = directoryOf(files.first().path).ifEmpty { "." }
        return listOf(ChangedFileGroup(fullDir, files))
    }
    return grouped.map { (directory, groupFiles) -> ChangedFileGroup(directory, groupFiles) }
}

private fun directoryOf(path: String): String {
    val index = path.lastIndexOf('/')
    return if (index < 0) "" else path.substring(0, index)
}

private fun commonDirectoryPrefix(paths: List<String>): String {
    val dirs = paths.map { directoryOf(it).split('/').filter(String::isNotEmpty) }
    val first = dirs.first()
    var count = first.size
    for (dir in dirs.drop(1)) {
        val shared = minOf(count, dir.size)
        var index = 0
        while (index < shared && dir[index] == first[index]) index += 1
        count = index
        if (count == 0) return ""
    }
    return first.take(count).joinToString("/")
}

private fun relativeDirectory(
    dir: String,
    prefix: String,
): String {
    if (prefix.isEmpty()) return dir.ifEmpty { "." }
    if (dir == prefix) return "."
    val prefixSlash = "$prefix/"
    return if (dir.startsWith(prefixSlash)) dir.removePrefix(prefixSlash) else dir.ifEmpty { "." }
}
