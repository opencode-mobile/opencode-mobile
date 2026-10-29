package dev.opencode.mobile.ui.settings

/** Settings sub-screens that render as the trailing pane of the tablet split view. */
enum class SettingsDetail {
    Servers,
    Appearance,
    ComponentGallery,
    FeatureFlags,
    DiagnosticLogs,
    ;

    /** Whether the screen lives in the Developer Tools section that Clear All Data locks. */
    val requiresDeveloperTools: Boolean
        get() =
            when (this) {
                Servers,
                Appearance,
                DiagnosticLogs,
                -> false
                ComponentGallery,
                FeatureFlags,
                -> true
            }

    /** The detail to show: a locked Developer Tools screen falls back to Servers. */
    fun resolved(developerToolsUnlocked: Boolean): SettingsDetail =
        if (requiresDeveloperTools && !developerToolsUnlocked) Servers else this
}
