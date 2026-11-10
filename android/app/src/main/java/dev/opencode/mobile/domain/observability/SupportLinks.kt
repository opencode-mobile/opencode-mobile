package dev.opencode.mobile

/** Links on Settings → About that let people support the project. */
internal object SupportLinks {
    const val GITHUB = SourceCode.URL
    const val X_PROFILE = "https://x.com/ivanfenenko"
    const val X_HANDLE = "@ivanfenenko"

    /** Play Store app page, where people rate and review. */
    fun playStoreMarket(packageName: String): String = "market://details?id=$packageName"

    /** Web fallback when no app handles `market://`. */
    fun playStoreWeb(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"
}
