package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.dto.FileNodeDto
import dev.opencode.mobile.data.remote.dto.PathDto

class FilesystemDomainAdapter {
    fun toServerPaths(dto: PathDto): ServerPaths =
        ServerPaths(
            home = dto.home,
            directory = dto.directory,
            worktree = dto.worktree,
        )

    fun toDirectoryNode(dto: FileNodeDto): DirectoryNode =
        DirectoryNode(
            name = dto.name,
            absolutePath = dto.absolute,
            isDirectory = dto.type == "directory",
        )
}
