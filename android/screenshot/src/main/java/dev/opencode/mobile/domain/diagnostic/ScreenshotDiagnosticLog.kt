package dev.opencode.mobile.domain.diagnostic

/**
 * Screenshot-module stand-in. The app keeps the real logger. The shared theme
 * sources only need these two calls while Paparazzi is compiling.
 */
enum class DiagnosticLogArea(
    val wireValue: String,
) {
    Connection("connection"),
}

object DiagnosticLog {
    @Suppress("UNUSED_PARAMETER")
    fun warning(
        area: DiagnosticLogArea,
        message: String,
        throwable: Throwable? = null,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun error(
        area: DiagnosticLogArea,
        message: String,
        throwable: Throwable? = null,
    ) = Unit
}
