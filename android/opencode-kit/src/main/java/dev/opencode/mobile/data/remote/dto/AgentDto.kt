package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AgentDto(
    val name: String,
    val description: String? = null,
    val mode: String,
    val hidden: Boolean? = null,
)
