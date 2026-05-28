package dev.opencode.mobile

object AppConfig {
/** Default dev URL for connection setup form only; runtime uses saved Connection Profile. */
  const val SERVER_BASE_URL = "http://127.0.0.1:4096/"

  /** Optional Basic Auth password (`OPENCODE_SERVER_PASSWORD`). */
  val SERVER_PASSWORD: String? = System.getenv("OPENCODE_SERVER_PASSWORD")
}
