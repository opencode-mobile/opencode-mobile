package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.dto.FileNodeDto
import dev.opencode.mobile.data.remote.dto.PathDto
import dev.opencode.mobile.data.remote.dto.V2FsEntryDto
import dev.opencode.mobile.data.remote.dto.V2LocationInfoDto

class FilesystemDomainAdapter {
    fun toServerPaths(dto: PathDto): ServerPaths =
        ServerPaths(
            home = dto.home,
            directory = dto.directory,
            worktree = dto.worktree,
        )

    fun toServerPaths(dto: V2LocationInfoDto): ServerPaths {
        val directory = dto.directory
        val worktree = dto.project?.directory ?: directory
        return ServerPaths(
            home = directory,
            directory = directory,
            worktree = worktree,
        )
    }

    fun toDirectoryNode(dto: FileNodeDto): DirectoryNode =
        DirectoryNode(
            name = dto.name,
            absolutePath = dto.absolute,
            isDirectory = dto.type == "directory",
        )

    fun toDirectoryNode(
        dto: V2FsEntryDto,
        listingDirectory: String,
    ): DirectoryNode {
        val absolutePath = absolutePath(dto.path, listingDirectory)
        return DirectoryNode(
            name = nameFromPath(absolutePath, dto.path),
            absolutePath = absolutePath,
            isDirectory = dto.type == "directory",
        )
    }

    fun toProjectEntry(dto: FileNodeDto): ProjectEntry {
        val relativePath = normalizeRelativePath(dto.path)
        return ProjectEntry(
            name = dto.name.ifBlank { relativePath.substringAfterLast('/') },
            relativePath = relativePath,
            isDirectory = dto.type == "directory",
            isIgnored = dto.ignored,
        )
    }

    /** v2 entries are relative to the location; directories end with `/`. */
    fun toProjectEntry(dto: V2FsEntryDto): ProjectEntry {
        val relativePath = normalizeRelativePath(dto.path)
        return ProjectEntry(
            name = relativePath.substringAfterLast('/'),
            relativePath = relativePath,
            isDirectory = dto.type == "directory" || dto.path.endsWith("/"),
            isIgnored = false,
        )
    }

    /** v1 `/find/file` returns bare relative paths of files. */
    fun toProjectFileEntry(relativePath: String): ProjectEntry {
        val normalized = normalizeRelativePath(relativePath)
        return ProjectEntry(
            name = normalized.substringAfterLast('/'),
            relativePath = normalized,
            isDirectory = false,
        )
    }

    private fun normalizeRelativePath(path: String): String =
        path
            .replace('\\', '/')
            .trim('/')
            .removePrefix("./")

    private fun absolutePath(
        path: String,
        listingDirectory: String,
    ): String {
        val normalized = stripTrailingSlashes(path)
        if (normalized.startsWith("/")) return normalized
        val relative = normalized.trimStart('/')
        val base = stripTrailingSlashes(listingDirectory)
        if (relative.isEmpty() || relative == ".") return base
        if (base == "/") return "/$relative"
        return "$base/$relative"
    }

    private fun nameFromPath(
        absolutePath: String,
        fallback: String,
    ): String =
        stripTrailingSlashes(absolutePath).substringAfterLast('/').ifEmpty {
            stripTrailingSlashes(fallback).substringAfterLast('/')
        }

    private fun stripTrailingSlashes(path: String): String {
        if (path == "/") return path
        val stripped = path.trimEnd('/')
        return stripped.ifEmpty { "/" }
    }
}
