package dev.opencode.mobile.domain.app

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

data class AppVersion(
    val version: String,
    val build: String,
) {
    companion object {
        const val FALLBACK_VERSION = "1.0"

        fun from(
            versionName: String?,
            versionCode: Long,
        ): AppVersion =
            AppVersion(
                version = versionName?.trim().orEmpty().ifEmpty { FALLBACK_VERSION },
                build = versionCode.toString(),
            )

        fun from(context: Context): AppVersion {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return from(
                versionName = packageInfo.versionName,
                versionCode = packageInfo.resolvedVersionCode(),
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.resolvedVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        versionCode.toLong()
    }
