package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.data.remote.PtyApi
import dev.opencode.mobile.data.remote.dto.CreatePtyRequestDto
import dev.opencode.mobile.data.remote.dto.PtySizeDto
import dev.opencode.mobile.data.remote.dto.UpdatePtyRequestDto

internal class V1TerminalRemote(
    private val api: PtyApi,
    private val adapter: TerminalDomainAdapter,
) : TerminalRemote {
    override suspend fun list(directory: String): List<TerminalSession> = api.list(directory).map(adapter::toSession)

    override suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession = adapter.toSession(api.create(CreatePtyRequestDto(cwd = cwd), directory))

    override suspend fun get(
        id: String,
        directory: String,
    ): TerminalSession = adapter.toSession(api.get(id, directory))

    override suspend fun updateSize(
        id: String,
        directory: String,
        cols: Int,
        rows: Int,
    ): TerminalSession =
        adapter.toSession(
            api.update(
                id,
                UpdatePtyRequestDto(size = PtySizeDto(rows = rows, cols = cols)),
                directory,
            ),
        )

    override suspend fun remove(
        id: String,
        directory: String,
    ) {
        api.delete(id, directory)
    }
}
