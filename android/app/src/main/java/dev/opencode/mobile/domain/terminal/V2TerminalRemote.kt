package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.data.remote.PtyApi
import dev.opencode.mobile.data.remote.dto.CreatePtyRequestDto
import dev.opencode.mobile.data.remote.dto.PtySizeDto
import dev.opencode.mobile.data.remote.dto.UpdatePtyRequestDto

internal class V2TerminalRemote(
    private val api: PtyApi,
    private val adapter: TerminalDomainAdapter,
) : TerminalRemote {
    override suspend fun list(directory: String): List<TerminalSession> = api.listV2(directory).map(adapter::toSession)

    override suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession = adapter.toSession(api.createV2(CreatePtyRequestDto(cwd = cwd), directory))

    override suspend fun get(
        id: String,
        directory: String,
    ): TerminalSession = adapter.toSession(api.getV2(id, directory))

    override suspend fun updateSize(
        id: String,
        directory: String,
        cols: Int,
        rows: Int,
    ): TerminalSession =
        adapter.toSession(
            api.updateV2(
                id,
                UpdatePtyRequestDto(size = PtySizeDto(rows = rows, cols = cols)),
                directory,
            ),
        )
}
