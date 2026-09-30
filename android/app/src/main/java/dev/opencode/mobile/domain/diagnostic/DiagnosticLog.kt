package dev.opencode.mobile.domain.diagnostic

import android.util.Log

/**
 * Dual-writes redacted diagnostic lines without requiring DI.
 * Prefer [DiagnosticLogger] at Hilt-injected call sites.
 */
object DiagnosticLog {
    fun debug(
        area: DiagnosticLogArea,
        message: String,
    ) {
        write(area, DiagnosticLogLevel.Debug, message)
    }

    fun info(
        area: DiagnosticLogArea,
        message: String,
    ) {
        write(area, DiagnosticLogLevel.Info, message)
    }

    fun warning(
        area: DiagnosticLogArea,
        message: String,
        throwable: Throwable? = null,
    ) {
        write(area, DiagnosticLogLevel.Warning, message, throwable)
    }

    fun error(
        area: DiagnosticLogArea,
        message: String,
        throwable: Throwable? = null,
    ) {
        write(area, DiagnosticLogLevel.Error, message, throwable)
    }

    private fun write(
        area: DiagnosticLogArea,
        level: DiagnosticLogLevel,
        message: String,
        throwable: Throwable? = null,
    ) {
        val redacted = DiagnosticLogRedactor.redact(DiagnosticLogCause.appendTo(message, throwable))
        if (level != DiagnosticLogLevel.Debug) {
            DiagnosticLogBuffer.shared.append(area = area, level = level, message = redacted)
        }
        val tag = "OpenCode.${area.wireValue}"
        runCatching {
            when (level) {
                DiagnosticLogLevel.Debug -> Log.d(tag, redacted)
                DiagnosticLogLevel.Info -> Log.i(tag, redacted)
                DiagnosticLogLevel.Warning ->
                    if (throwable == null) Log.w(tag, redacted) else Log.w(tag, redacted, throwable)
                DiagnosticLogLevel.Error ->
                    if (throwable == null) Log.e(tag, redacted) else Log.e(tag, redacted, throwable)
            }
        }
    }
}
