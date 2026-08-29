package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.data.remote.dto.PtyDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class TerminalDomainAdapter {
    fun toSession(dto: PtyDto): TerminalSession =
        TerminalSession(
            id = dto.id,
            title = dto.title,
            command = dto.command,
            cwd = dto.cwd,
            status = toStatus(dto.status),
        )

    fun mapError(throwable: Throwable): TerminalError =
        when (throwable) {
            is TerminalError -> throwable
            is ResponseException ->
                TerminalError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            is JsonConvertException -> TerminalError.Decoding(throwable)
            else -> TerminalError.Transport(throwable)
        }

    private fun toStatus(raw: String): TerminalSessionStatus =
        if (raw.equals("running", ignoreCase = true)) {
            TerminalSessionStatus.Running
        } else {
            TerminalSessionStatus.Exited
        }
}
