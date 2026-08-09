package dev.opencode.mobile

import android.app.Application

/**
 * Debug no-op for the shared release-observability seam (OPE-123).
 * Does not import or initialize Firebase; feature code must call only this entry.
 */
internal object OpenCodeReleaseObservability {
    fun initialize(application: Application) = Unit
}
