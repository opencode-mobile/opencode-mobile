package dev.opencode.mobile.network

import android.content.Context
import dev.opencode.mobile.AppConfig

data class ServerConnection(
  val baseUrl: String,
  val username: String = DEFAULT_USERNAME,
  val password: String? = null,
) {
  val apiBaseUrl: String =
    baseUrl.trimEnd('/') + '/'

  companion object {
    const val DEFAULT_USERNAME: String = "opencode"

    fun fromAppConfig(context: Context): ServerConnection =
      ServerConnection(
        baseUrl = resolveDevServerBaseUrl(context),
        password = AppConfig.SERVER_PASSWORD,
      )
  }
}
