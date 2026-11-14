package dev.opencode.mobile.domain.diagnostic

enum class DiagnosticLogExportWindow(
    val minutes: Int,
) {
    FiveMinutes(5),
    FifteenMinutes(15),
    SixtyMinutes(60),
    ;

    val milliseconds: Long get() = minutes * 60L * 1_000L

    val label: String get() = "Last $minutes minutes"

    companion object {
        val DEFAULT = FifteenMinutes
    }
}
