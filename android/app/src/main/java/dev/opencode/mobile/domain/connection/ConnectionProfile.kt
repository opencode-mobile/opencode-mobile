package dev.opencode.mobile.domain.connection

data class ConnectionProfile(
  val baseUrl: String,
  val username: String,
  val password: String = "",
)
