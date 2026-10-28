package dev.opencode.mobile.platform

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** The app's signing certificate, as Google API keys restricted to an Android app expect it. */
object SigningCertificate {
    /** SHA-1 of the current signing certificate, uppercase hex with no colons; null if unreadable. */
    fun sha1Hex(context: Context): String? {
        val signature = runCatching { currentSignature(context) }.getOrNull() ?: return null
        return sha1Hex(signature.toByteArray())
    }

    fun sha1Hex(certificate: ByteArray): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(certificate)
            .joinToString(separator = "") { byte -> "%02X".format(byte) }

    @Suppress("DEPRECATION")
    private fun currentSignature(context: Context): Signature? {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The certificate that signed this APK, after any key rotation.
            packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
        } else {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    }
}
