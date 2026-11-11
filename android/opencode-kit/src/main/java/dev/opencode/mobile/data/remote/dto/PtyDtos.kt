package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class PtyDto(
    val id: String,
    val title: String = "",
    val command: String = "",
    val cwd: String = "",
    val status: String = "",
    val args: List<String> = emptyList(),
    val pid: Int? = null,
    val exitCode: Int? = null,
)

@Serializable
data class CreatePtyRequestDto(
    val cwd: String,
)

@Serializable
data class UpdatePtyRequestDto(
    val size: PtySizeDto,
)

@Serializable
data class PtySizeDto(
    val rows: Int,
    val cols: Int,
)
