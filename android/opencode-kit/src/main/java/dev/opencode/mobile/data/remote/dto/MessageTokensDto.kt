package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class MessageTokensDto(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: MessageTokenCacheDto? = null,
)

@Serializable
data class MessageTokenCacheDto(
    val read: Long? = null,
    val write: Long? = null,
)
