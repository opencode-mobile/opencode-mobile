package dev.opencode.mobile.domain.session

/** Title prefilled in every fork name dialog (Home, Session Chat overflow, message actions, Commands). */
object SessionForkTitle {
    const val FALLBACK = "Forked session"

    fun suggested(currentTitle: String): String {
        val trimmed = currentTitle.trim()
        return if (trimmed.isEmpty()) FALLBACK else "Fork of $trimmed"
    }
}
