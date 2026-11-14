package dev.opencode.mobile.domain.serverhealth

/** Wall-clock milliseconds, replaceable in tests. */
fun interface ServerHealthClock {
    fun nowMs(): Long

    companion object {
        val System = ServerHealthClock { java.lang.System.currentTimeMillis() }
    }
}
