package dev.opencode.mobile

object AppConfig {
  /** Host machine URL; emulators rewrite loopback to `10.0.2.2` in [resolveDevServerBaseUrl]. */
  const val SERVER_BASE_URL = "http://127.0.0.1:4096/"

  /** Optional Basic Auth password (`OPENCODE_SERVER_PASSWORD`). */
  val SERVER_PASSWORD: String? = System.getenv("OPENCODE_SERVER_PASSWORD")
}
