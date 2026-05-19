package dev.opencode.mobile.network

import android.content.Context
import android.os.Build
import dev.opencode.mobile.AppConfig

/**
 * Resolves [AppConfig.SERVER_BASE_URL] for the current device.
 *
 * The Android emulator's `127.0.0.1` is the emulator itself, not the host machine where
 * `opencode serve` runs. Use `10.0.2.2` to reach the host loopback (same as iOS Simulator
 * using `127.0.0.1` on the Mac).
 */
fun resolveDevServerBaseUrl(context: Context): String {
  val configured = AppConfig.SERVER_BASE_URL
  if (!isAndroidEmulator()) {
    return configured
  }
  return configured
    .replace("127.0.0.1", EMULATOR_HOST_LOOPBACK)
    .replace("localhost", EMULATOR_HOST_LOOPBACK)
}

private const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"

private fun isAndroidEmulator(): Boolean {
  return Build.FINGERPRINT.startsWith("generic") ||
    Build.FINGERPRINT.startsWith("unknown") ||
    Build.FINGERPRINT.contains("emulator") ||
    Build.MODEL.contains("google_sdk") ||
    Build.MODEL.contains("Emulator") ||
    Build.MODEL.contains("Android SDK built for") ||
    Build.MANUFACTURER.contains("Genymotion") ||
    Build.HARDWARE.contains("goldfish") ||
    Build.HARDWARE.contains("ranchu") ||
    Build.PRODUCT.contains("sdk_gphone")
}
