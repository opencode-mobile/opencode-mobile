package dev.opencode.hexcode.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Hex Code's privacy policy, the page its store listings link to. It opens in the system browser: the in-app browser
 * is for pages on the user's own computer.
 */
object HexPrivacyPolicy {
    /** Not translated: the page is the same in every language. */
    const val URL = "https://opencode-mobile.github.io/opencode-mobile-docs/hexcode/privacy/"

    private const val TAG = "HexPrivacyPolicy"

    fun open(context: Context) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(URL)).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "no browser opens the privacy policy", error)
        }
    }
}
