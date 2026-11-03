package dev.opencode.mobile.ui.onboarding

import androidx.annotation.StringRes
import dev.opencode.mobile.R
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic

internal data class AppOnboardingFeatureRow(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
)

internal data class AppOnboardingPage(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int?,
    @param:StringRes val badge: Int? = null,
    @param:StringRes val setupLink: Int? = null,
    val features: List<AppOnboardingFeatureRow> = emptyList(),
    val illustration: OpenCodeSkeletonIllustrationTopic,
)

internal object AppOnboardingCopy {
    const val SERVER_DOCS_URL = "https://opencode.ai/docs/server/"

    val pages: List<AppOnboardingPage> =
        listOf(
            AppOnboardingPage(
                id = "unofficial",
                title = R.string.onboarding_unofficial_title,
                body = R.string.onboarding_unofficial_body,
                badge = R.string.onboarding_unofficial_badge,
                illustration = OpenCodeSkeletonIllustrationTopic.Unofficial,
            ),
            AppOnboardingPage(
                id = "connect",
                title = R.string.onboarding_connect_page_title,
                body = R.string.onboarding_connect_page_body,
                setupLink = R.string.onboarding_connect_page_setup_link,
                illustration = OpenCodeSkeletonIllustrationTopic.ConnectForm,
            ),
            AppOnboardingPage(
                id = "features",
                title = R.string.onboarding_features_title,
                body = null,
                illustration = OpenCodeSkeletonIllustrationTopic.SessionsLive,
                features =
                    listOf(
                        AppOnboardingFeatureRow(
                            id = "sessions",
                            title = R.string.onboarding_features_sessions_title,
                            detail = R.string.onboarding_features_sessions_detail,
                        ),
                        AppOnboardingFeatureRow(
                            id = "chat",
                            title = R.string.onboarding_features_chat_title,
                            detail = R.string.onboarding_features_chat_detail,
                        ),
                        AppOnboardingFeatureRow(
                            id = "permissions",
                            title = R.string.onboarding_features_permissions_title,
                            detail = R.string.onboarding_features_permissions_detail,
                        ),
                        AppOnboardingFeatureRow(
                            id = "changes",
                            title = R.string.onboarding_features_changes_title,
                            detail = R.string.onboarding_features_changes_detail,
                        ),
                        AppOnboardingFeatureRow(
                            id = "live",
                            title = R.string.onboarding_features_live_title,
                            detail = R.string.onboarding_features_live_detail,
                        ),
                    ),
            ),
        )
}
