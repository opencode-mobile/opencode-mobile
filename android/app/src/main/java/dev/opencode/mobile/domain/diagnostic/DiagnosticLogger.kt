package dev.opencode.mobile.domain.diagnostic

import javax.inject.Inject
import javax.inject.Singleton

/** Hilt-facing facade that dual-writes via [DiagnosticLog]. */
@Singleton
class DiagnosticLogger
    @Inject
    constructor() {
        fun debug(
            area: DiagnosticLogArea,
            message: String,
        ) {
            DiagnosticLog.debug(area, message)
        }

        fun info(
            area: DiagnosticLogArea,
            message: String,
        ) {
            DiagnosticLog.info(area, message)
        }

        fun warning(
            area: DiagnosticLogArea,
            message: String,
            throwable: Throwable? = null,
        ) {
            DiagnosticLog.warning(area, message, throwable)
        }

        fun error(
            area: DiagnosticLogArea,
            message: String,
            throwable: Throwable? = null,
        ) {
            DiagnosticLog.error(area, message, throwable)
        }
    }
