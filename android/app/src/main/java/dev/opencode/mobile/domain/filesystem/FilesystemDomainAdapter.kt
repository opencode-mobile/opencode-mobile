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

    private fun absolutePath(
        path: String,
        listingDirectory: String,
    ): String {
        if (path.startsWith("/")) return path
        val base = listingDirectory.trimEnd('/')
        val relative = path.trimStart('/')
        if (relative.isEmpty() || relative == ".") return listingDirectory
        return "$base/$relative"
    }

    private fun nameFromPath(
        absolutePath: String,
        fallback: String,
    ): String = absolutePath.substringAfterLast('/').ifEmpty { fallback.substringAfterLast('/') }
}
