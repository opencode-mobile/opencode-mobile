package dev.opencode.mobile.ui.onboarding

internal data class AppOnboardingFeatureRow(
    val id: String,
    val title: String,
    val detail: String,
)

internal data class AppOnboardingPage(
    val id: String,
    val title: String,
    val body: String?,
    val badge: String? = null,
    val setupLink: String? = null,
    val features: List<AppOnboardingFeatureRow> = emptyList(),
)

internal object AppOnboardingCopy {
    const val SERVER_DOCS_URL = "https://opencode.ai/docs/server/"
    const val ILLUSTRATION_PLACEHOLDER = "Illustration placeholder"

    val pages: List<AppOnboardingPage> =
        listOf(
            AppOnboardingPage(
                id = "unofficial",
                title = "An unofficial\nopencode client",
                body =
                    "OpenCode Mobile is a native client for opencode sessions — work in progress. " +
                        "It is not an official OpenCode product.",
                badge = "Unofficial",
            ),
            AppOnboardingPage(
                id = "connect",
                title = "Connect to\nyour servers",
                body =
                    "Point the app at an opencode server on your machine. We recommend Tailscale " +
                        "so your phone can reach it securely from anywhere.",
                setupLink = "Need the server first?",
            ),
            AppOnboardingPage(
                id = "features",
                title = "What you can\ndo from here",
                body = null,
                features =
                    listOf(
                        AppOnboardingFeatureRow(
                            id = "sessions",
                            title = "Sessions",
                            detail = "List, open, create, rename, fork, archive, delete",
                        ),
                        AppOnboardingFeatureRow(
                            id = "chat",
                            title = "Chat & abort",
                            detail = "Send prompts and stop work in flight",
                        ),
                        AppOnboardingFeatureRow(
                            id = "permissions",
                            title = "Permissions",
                            detail = "Approve or deny requests (no lasting remember)",
                        ),
                        AppOnboardingFeatureRow(
                            id = "changes",
                            title = "Changes",
                            detail = "Inspect session diffs",
                        ),
                        AppOnboardingFeatureRow(
                            id = "live",
                            title = "Live while open",
                            detail = "Foreground event stream; refresh after reconnect",
                        ),
                    ),
            ),
        )
}
