package dev.opencode.mobile.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.PrivacyPolicy
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.SourceCode
import dev.opencode.mobile.SupportLinks
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.HomeSessionRowStyle
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.components.openCodeBottomSheetScrollTarget
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.resolve

@Composable
fun SettingsScreen(
    onDeveloperToolsClick: () -> Unit,
    onDiagnosticLogsClick: () -> Unit,
    onNavigateBack: () -> Unit,
    onWhatsNewClick: () -> Unit = {},
    /** Shows the New tag on What's new from when the sheet appeared until the history is opened. */
    whatsNewBadgePending: Boolean = false,
    onServersClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onSendFeedbackClick: () -> Unit = {},
    onClearData: () -> Unit,
    onClearSessionCache: () -> Unit,
    sessionCacheSizeLabel: UiText,
    onRefreshSessionCacheSize: () -> Unit,
    version: String,
    build: String,
    hideUserDirectoryPrefix: Boolean,
    onHideUserDirectoryPrefixChange: (Boolean) -> Unit,
    showsWorktrees: Boolean = true,
    onShowsWorktreesChange: (Boolean) -> Unit = {},
    sessionRowStyle: HomeSessionRowStyle = HomeSessionRowStyle.DEFAULT,
    onSessionRowStyleChange: (HomeSessionRowStyle) -> Unit = {},
    recentWindowHours: Int,
    onRecentWindowHoursChange: (Int) -> Unit,
    collapseConsecutiveToolCalls: Boolean,
    onCollapseConsecutiveToolCallsChange: (Boolean) -> Unit,
    timelineStyle: SessionChatTimelineStyle,
    onTimelineStyleChange: (SessionChatTimelineStyle) -> Unit,
    chipTimelineAvailable: Boolean = true,
    followUpDelivery: FollowUpDelivery,
    onFollowUpDeliveryChange: (FollowUpDelivery) -> Unit,
    supportsFollowUpDelivery: Boolean,
    productAnalyticsConsented: Boolean,
    onProductAnalyticsConsentedChange: (Boolean) -> Unit,
    appLockEnabled: Boolean = false,
    appLockAvailable: Boolean = false,
    onAppLockEnabledChange: (Boolean) -> Unit = {},
    serversSummary: ServersSummary = ServersSummary(),
    modifier: Modifier = Modifier,
    selectedDetail: SettingsDetail? = null,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showClearSessionCacheConfirmation by remember { mutableStateOf(false) }
    var showRecentWindowPicker by remember { mutableStateOf(false) }
    var showFollowUpPicker by remember { mutableStateOf(false) }
    var showTimelineStylePicker by remember { mutableStateOf(false) }
    var showSessionRowStylePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentTheme = ThemeManager.currentTheme
    val currentSyntax = ThemeManager.currentSyntaxPreset
    val themeLoadError = ThemeManager.loadError

    LaunchedEffect(Unit) {
        onRefreshSessionCacheSize()
    }

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_RECENT_WINDOW_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = showRecentWindowPicker,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_CACHE_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = showClearSessionCacheConfirmation,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_ALL_DATA_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = showClearConfirmation,
    )

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = stringResource(R.string.settings_title),
            leading =
                OpenCodeTopBarLeading.Back(
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_BACK)
                        onNavigateBack()
                    },
                ),
            showDivider = true,
        )

        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_server))

                OpenCodeListItem(
                    title = stringResource(R.string.servers_title),
                    selected = selectedDetail == SettingsDetail.Servers,
                    subtitle = serversSummary.subtitle.resolve(),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator =
                        StartDecorator.Letter(
                            serversSummary.letter,
                            serversSummary.letterAccent,
                        ),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_CONNECTION)
                        onServersClick()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_help_feedback))

                val sendFeedbackTitle = stringResource(R.string.settings_send_feedback_title)
                OpenCodeListItem(
                    title = sendFeedbackTitle,
                    subtitle = stringResource(R.string.settings_send_feedback_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Edit, sendFeedbackTitle),
                    onClick = onSendFeedbackClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_appearance))

                val appearanceTitle = stringResource(R.string.appearance_title)
                OpenCodeListItem(
                    title = appearanceTitle,
                    selected = selectedDetail == SettingsDetail.Appearance,
                    // Preset names are theme data, shown as is.
                    subtitle = listOf(currentTheme.name, currentSyntax.name).joinToString(" · "),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, appearanceTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_APPEARANCE)
                        onAppearanceClick()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val hideUserDirectoryTitle = stringResource(R.string.settings_hide_user_directory_title)
                OpenCodeListItem(
                    title = hideUserDirectoryTitle,
                    subtitle = stringResource(R.string.settings_hide_user_directory_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, hideUserDirectoryTitle),
                    accessory =
                        OpenCodeListItemAccessory.Toggle(
                            checked = hideUserDirectoryPrefix,
                            onCheckedChange = { enabled ->
                                logSettingsToggle(
                                    ProductAnalytics.Action.SETTINGS_TOGGLE_HIDE_USER_DIRECTORY,
                                    enabled,
                                )
                                onHideUserDirectoryPrefixChange(enabled)
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val recentWindowTitle = stringResource(R.string.settings_recent_window_title)
                OpenCodeListItem(
                    title = recentWindowTitle,
                    subtitle = recentWindowHoursLabel(recentWindowHours).resolve(),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.List, recentWindowTitle),
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_RECENT_WINDOW_PICKER)
                        showRecentWindowPicker = true
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val collapseToolCallsTitle = stringResource(R.string.settings_collapse_tool_calls_title)
                OpenCodeListItem(
                    title = collapseToolCallsTitle,
                    subtitle = stringResource(R.string.settings_collapse_tool_calls_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator =
                        StartDecorator.Icon(OpenCodeIcons.CollapseContent, collapseToolCallsTitle),
                    accessory =
                        OpenCodeListItemAccessory.Toggle(
                            checked = collapseConsecutiveToolCalls,
                            onCheckedChange = { enabled ->
                                logSettingsToggle(
                                    ProductAnalytics.Action.SETTINGS_TOGGLE_COLLAPSE_CONSECUTIVE_TOOL_CALLS,
                                    enabled,
                                )
                                onCollapseConsecutiveToolCallsChange(enabled)
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val timelineStyleTitle = stringResource(R.string.settings_timeline_style_title)
                OpenCodeListItem(
                    title = timelineStyleTitle,
                    subtitle = stringResource(timelineStyle.settingsTitleRes()),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, timelineStyleTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = { showTimelineStylePicker = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val followUpTitle = stringResource(R.string.settings_follow_up_title)
                OpenCodeListItem(
                    title = followUpTitle,
                    subtitle =
                        stringResource(
                            when {
                                !supportsFollowUpDelivery -> R.string.settings_follow_up_unsupported
                                followUpDelivery == FollowUpDelivery.Steer -> R.string.settings_follow_up_subtitle_steer
                                else -> R.string.settings_follow_up_subtitle_queue
                            },
                        ),
                    style = OpenCodeListItemStyle.Card,
                    enabled = supportsFollowUpDelivery,
                    startDecorator =
                        StartDecorator.Icon(
                            if (followUpDelivery == FollowUpDelivery.Steer && supportsFollowUpDelivery) {
                                OpenCodeIcons.ArrowWarmUp
                            } else {
                                OpenCodeIcons.ArrowUpward
                            },
                            followUpTitle,
                        ),
                    accessory = if (supportsFollowUpDelivery) OpenCodeListItemAccessory.Disclosure else null,
                    onClick =
                        if (supportsFollowUpDelivery) {
                            { showFollowUpPicker = true }
                        } else {
                            null
                        },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                themeLoadError?.let { error ->
                    OpenCodeInlineNotice(
                        message = error.resolve(),
                        tone = OpenCodeInlineNoticeTone.Error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_home))

                val worktreesTitle = stringResource(R.string.settings_worktrees_title)
                OpenCodeListItem(
                    title = worktreesTitle,
                    subtitle = stringResource(R.string.settings_worktrees_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, worktreesTitle),
                    accessory =
                        OpenCodeListItemAccessory.Toggle(
                            checked = showsWorktrees,
                            onCheckedChange = { enabled ->
                                logSettingsToggle(ProductAnalytics.Action.SETTINGS_TOGGLE_WORKTREES, enabled)
                                onShowsWorktreesChange(enabled)
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settingsWorktreesToggle"),
                )

                val sessionRowsTitle = stringResource(R.string.settings_session_rows_title)
                OpenCodeListItem(
                    title = sessionRowsTitle,
                    subtitle = stringResource(sessionRowStyle.settingsTitleRes()),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.List, sessionRowsTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_SESSION_ROWS_PICKER)
                        showSessionRowStylePicker = true
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settingsSessionRows"),
                )

                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_session_cache))

                OpenCodeListItem(
                    title = stringResource(R.string.settings_session_cache_size),
                    subtitle = sessionCacheSizeLabel.resolve(),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator =
                        StartDecorator.Icon(OpenCodeIcons.Archive, stringResource(R.string.settings_session_cache_size_label)),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val clearSessionCacheTitle = stringResource(R.string.settings_clear_session_cache_title)
                OpenCodeListItem(
                    title = clearSessionCacheTitle,
                    subtitle = stringResource(R.string.settings_clear_session_cache_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Close, clearSessionCacheTitle),
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_CLEAR_SESSION_CACHE)
                        showClearSessionCacheConfirmation = true
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_privacy))

                val appLockTitle = stringResource(R.string.settings_app_lock_title)
                OpenCodeListItem(
                    title = appLockTitle,
                    subtitle =
                        stringResource(
                            if (appLockAvailable) {
                                R.string.settings_app_lock_subtitle_available
                            } else {
                                R.string.settings_app_lock_subtitle_unavailable
                            },
                        ),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Lock, appLockTitle),
                    accessory =
                        OpenCodeListItemAccessory.Toggle(
                            checked = appLockEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !appLockAvailable) return@Toggle
                                onAppLockEnabledChange(enabled)
                                logSettingsToggle(ProductAnalytics.Action.SETTINGS_TOGGLE_APP_LOCK, enabled)
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val privacyPolicyTitle = stringResource(R.string.settings_privacy_policy_title)
                OpenCodeListItem(
                    title = privacyPolicyTitle,
                    subtitle = stringResource(R.string.settings_privacy_policy_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, privacyPolicyTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_PRIVACY_POLICY)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PrivacyPolicy.URL)),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = stringResource(R.string.settings_analytics_title),
                    subtitle = stringResource(R.string.settings_analytics_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator =
                        StartDecorator.Icon(OpenCodeIcons.Description, stringResource(R.string.settings_analytics_label)),
                    accessory =
                        OpenCodeListItemAccessory.Toggle(
                            checked = productAnalyticsConsented,
                            onCheckedChange = { enabled ->
                                // Log after the consent change so a revoke is dropped by the
                                // consent gate instead of being recorded on the way out.
                                onProductAnalyticsConsentedChange(enabled)
                                logSettingsToggle(
                                    ProductAnalytics.Action.SETTINGS_TOGGLE_PRODUCT_ANALYTICS,
                                    enabled,
                                )
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val diagnosticLogsTitle = stringResource(R.string.diagnostics_title)
                OpenCodeListItem(
                    title = diagnosticLogsTitle,
                    selected = selectedDetail == SettingsDetail.DiagnosticLogs,
                    subtitle = stringResource(R.string.settings_diagnostic_logs_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, diagnosticLogsTitle),
                    onClick = onDiagnosticLogsClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val clearAllDataTitle = stringResource(R.string.settings_clear_all_data_title)
                OpenCodeListItem(
                    title = clearAllDataTitle,
                    subtitle = stringResource(R.string.settings_clear_all_data_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Close, clearAllDataTitle),
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_CLEAR_ALL_DATA)
                        showClearConfirmation = true
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeSectionHeader(title = stringResource(R.string.settings_section_about))

                val whatsNewTitle = stringResource(R.string.whats_new_title)
                OpenCodeListItem(
                    title = whatsNewTitle,
                    subtitle = stringResource(R.string.whats_new_settings_subtitle),
                    isNew = whatsNewBadgePending,
                    selected = selectedDetail == SettingsDetail.WhatsNew,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Sparkles, whatsNewTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onWhatsNewClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val versionTitle = stringResource(R.string.settings_version)
                OpenCodeListItem(
                    title = versionTitle,
                    subtitle = version,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, versionTitle),
                    onClick = { copyAboutValueToClipboard(context, label = versionTitle, value = version) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val buildTitle = stringResource(R.string.settings_build)
                OpenCodeListItem(
                    title = buildTitle,
                    subtitle = build,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Settings, buildTitle),
                    onClick = { copyAboutValueToClipboard(context, label = buildTitle, value = build) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val sourceCodeTitle = stringResource(R.string.settings_source_code_title)
                OpenCodeListItem(
                    title = sourceCodeTitle,
                    subtitle = stringResource(R.string.settings_source_code_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Code, sourceCodeTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SourceCode.URL)))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                val rateAppTitle = stringResource(R.string.settings_rate_app_title)
                OpenCodeListItem(
                    title = rateAppTitle,
                    subtitle = stringResource(R.string.settings_rate_app_subtitle),
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.RateReview, rateAppTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_RATE_APP)
                        openPlayStoreListing(context)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settingsRateApp"),
                )

                val starOnGitHubTitle = stringResource(R.string.settings_star_on_github_title)
                OpenCodeListItem(
                    title = starOnGitHubTitle,
                    subtitle = "opencode-mobile/opencode-mobile",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Star, starOnGitHubTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_STAR_ON_GITHUB)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SupportLinks.GITHUB)))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settingsStarOnGitHub"),
                )

                val followOnXTitle = stringResource(R.string.settings_follow_on_x_title)
                OpenCodeListItem(
                    title = followOnXTitle,
                    subtitle = SupportLinks.X_HANDLE,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.AtSign, followOnXTitle),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_FOLLOW_ON_X)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SupportLinks.X_PROFILE)))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settingsFollowOnX"),
                )

                OpenCodeListItem(
                    title = DeveloperToolsCopy.TITLE,
                    selected = selectedDetail == SettingsDetail.DeveloperTools,
                    subtitle = DeveloperToolsCopy.SETTINGS_SUBTITLE,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Terminal, DeveloperToolsCopy.TITLE),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onDeveloperToolsClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    OpenCodeBottomSheet(
        visible = showRecentWindowPicker,
        title = stringResource(R.string.settings_recent_window_title),
        scrollTargetKey = recentWindowHours,
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SETTINGS_RECENT_WINDOW_PICKER,
                actionId = ProductAnalytics.Action.RECENT_WINDOW_DISMISS,
            )
            showRecentWindowPicker = false
        },
    ) {
        Text(
            text = stringResource(R.string.settings_recent_window_message),
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
            modifier = Modifier.openCodeBottomSheetContentInset().padding(bottom = 8.dp),
        )
        HomeSessionLadder.ALLOWED_WINDOW_HOURS.forEach { hours ->
            OpenCodeBottomSheetEntry(
                title = recentWindowHoursLabel(hours).resolve(),
                selected = hours == recentWindowHours,
                modifier = Modifier.openCodeBottomSheetScrollTarget(hours),
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_RECENT_WINDOW_PICKER,
                        actionId = ProductAnalytics.Action.RECENT_WINDOW_SELECT_HOURS,
                        parameters = mapOf(ProductAnalytics.Param.HOURS to hours.toString()),
                    )
                    onRecentWindowHoursChange(hours)
                    showRecentWindowPicker = false
                },
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showTimelineStylePicker,
        title = stringResource(R.string.settings_timeline_style_title),
        onDismissRequest = { showTimelineStylePicker = false },
    ) {
        SessionChatTimelineStyle
            .available(chipTimelineAvailable)
            .forEach { style ->
                OpenCodeBottomSheetEntry(
                    title = stringResource(style.settingsTitleRes()),
                    subtitle = stringResource(style.settingsSubtitleRes()),
                    selected = style == timelineStyle,
                    onClick = {
                        // Chips keeps the existing toggle event; leaving Chips logs it disabled.
                        if (style == SessionChatTimelineStyle.Chips || timelineStyle == SessionChatTimelineStyle.Chips) {
                            logSettingsToggle(
                                ProductAnalytics.Action.SETTINGS_TOGGLE_CHIP_TIMELINE,
                                style == SessionChatTimelineStyle.Chips,
                            )
                        }
                        onTimelineStyleChange(style)
                        showTimelineStylePicker = false
                    },
                )
            }
    }

    OpenCodeBottomSheet(
        visible = showSessionRowStylePicker,
        title = stringResource(R.string.settings_session_rows_title),
        onDismissRequest = { showSessionRowStylePicker = false },
    ) {
        HomeSessionRowStyle.entries.forEach { style ->
            OpenCodeBottomSheetEntry(
                title = stringResource(style.settingsTitleRes()),
                subtitle = stringResource(style.settingsSubtitleRes()),
                selected = style == sessionRowStyle,
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS,
                        actionId = ProductAnalytics.Action.SETTINGS_SESSION_ROWS,
                        parameters = mapOf(ProductAnalytics.Param.MODE to style.wireValue),
                    )
                    onSessionRowStyleChange(style)
                    showSessionRowStylePicker = false
                },
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showFollowUpPicker,
        title = stringResource(R.string.settings_follow_up_title),
        onDismissRequest = { showFollowUpPicker = false },
    ) {
        FollowUpDelivery.entries.forEach { mode ->
            OpenCodeBottomSheetEntry(
                title =
                    stringResource(
                        if (mode == FollowUpDelivery.Steer) R.string.settings_follow_up_steer else R.string.settings_follow_up_queue,
                    ),
                startDecorator =
                    BottomSheetEntryDecorator.Icon(
                        if (mode ==
                            FollowUpDelivery.Steer
                        ) {
                            OpenCodeIcons.ArrowWarmUp
                        } else {
                            OpenCodeIcons.ArrowUpward
                        },
                    ),
                selected = mode == followUpDelivery,
                onClick = {
                    onFollowUpDeliveryChange(mode)
                    showFollowUpPicker = false
                },
            )
        }
    }

    OpenCodeDialog(
        visible = showClearSessionCacheConfirmation,
        title = stringResource(R.string.settings_clear_session_cache_dialog_title),
        subtitle = stringResource(R.string.settings_clear_session_cache_dialog_message),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.settings_clear),
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_CACHE_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_CACHE_CONFIRM,
                    )
                    showClearSessionCacheConfirmation = false
                    onClearSessionCache()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_CACHE_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_CACHE_CANCEL,
                    )
                    showClearSessionCacheConfirmation = false
                },
            ),
        onDismissRequest = { showClearSessionCacheConfirmation = false },
    )

    OpenCodeDialog(
        visible = showClearConfirmation,
        title = stringResource(R.string.settings_clear_all_data_dialog_title),
        subtitle = stringResource(R.string.settings_clear_all_data_dialog_message),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.settings_clear),
                onClick = {
                    // Clearing all data also clears consent, so this must be logged first.
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_ALL_DATA_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_ALL_DATA_CONFIRM,
                    )
                    showClearConfirmation = false
                    onClearData()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_ALL_DATA_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_ALL_DATA_CANCEL,
                    )
                    showClearConfirmation = false
                },
            ),
        onDismissRequest = { showClearConfirmation = false },
    )
}

private fun HomeSessionRowStyle.settingsTitleRes(): Int =
    when (this) {
        HomeSessionRowStyle.Compact -> R.string.settings_session_rows_compact_title
        HomeSessionRowStyle.Classic -> R.string.settings_session_rows_classic_title
    }

private fun HomeSessionRowStyle.settingsSubtitleRes(): Int =
    when (this) {
        HomeSessionRowStyle.Compact -> R.string.settings_session_rows_compact_subtitle
        HomeSessionRowStyle.Classic -> R.string.settings_session_rows_classic_subtitle
    }

internal fun SessionChatTimelineStyle.settingsTitleRes(): Int =
    when (this) {
        SessionChatTimelineStyle.Standard -> R.string.settings_timeline_style_standard_title
        SessionChatTimelineStyle.Chips -> R.string.settings_timeline_style_chips_title
        SessionChatTimelineStyle.StepRail -> R.string.settings_timeline_style_step_rail_title
        SessionChatTimelineStyle.ActivityCard -> R.string.settings_timeline_style_activity_card_title
    }

internal fun SessionChatTimelineStyle.settingsSubtitleRes(): Int =
    when (this) {
        SessionChatTimelineStyle.Standard -> R.string.settings_timeline_style_standard_subtitle
        SessionChatTimelineStyle.Chips -> R.string.settings_timeline_style_chips_subtitle
        SessionChatTimelineStyle.StepRail -> R.string.settings_timeline_style_step_rail_subtitle
        SessionChatTimelineStyle.ActivityCard -> R.string.settings_timeline_style_activity_card_subtitle
    }

/** "4 hours" for the Recent Window picker and its row. */
internal fun recentWindowHoursLabel(hours: Int): UiText {
    val sanitized = HomeSessionLadder.sanitizeWindowHours(hours)
    return UiText.plural(R.plurals.settings_recent_window_hours, sanitized)
}

private fun logSettingsAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SETTINGS,
        actionId = actionId,
    )
}

/** Opens this app's Play Store page, or its web page when no store app is installed. */
private fun openPlayStoreListing(context: Context) {
    val packageName = context.packageName
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SupportLinks.playStoreMarket(packageName))))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SupportLinks.playStoreWeb(packageName))))
    }
}

private fun copyAboutValueToClipboard(
    context: Context,
    label: String,
    value: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun logSettingsToggle(
    actionId: String,
    enabled: Boolean,
) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SETTINGS,
        actionId = actionId,
        parameters = mapOf(ProductAnalytics.Param.OUTCOME to if (enabled) "enabled" else "disabled"),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        SettingsScreen(
            onDeveloperToolsClick = {},
            onDiagnosticLogsClick = {},
            onNavigateBack = {},
            onServersClick = {},
            onAppearanceClick = {},
            onClearData = {},
            onClearSessionCache = {},
            sessionCacheSizeLabel = UiText.resource(R.string.settings_session_cache_empty),
            onRefreshSessionCacheSize = {},
            version = "1.0",
            build = "1",
            hideUserDirectoryPrefix = HomeDisplayPreferences.hideUserDirectoryPrefix,
            onHideUserDirectoryPrefixChange = {},
            recentWindowHours = HomeDisplayPreferences.recentWindowHours,
            onRecentWindowHoursChange = {},
            collapseConsecutiveToolCalls =
                SessionChatDisplayPreferences.collapseConsecutiveToolCalls,
            onCollapseConsecutiveToolCallsChange = {},
            timelineStyle = SessionChatDisplayPreferences.timelineStyle,
            onTimelineStyleChange = {},
            followUpDelivery = FollowUpDelivery.Steer,
            onFollowUpDeliveryChange = {},
            supportsFollowUpDelivery = true,
            productAnalyticsConsented = false,
            onProductAnalyticsConsentedChange = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        SettingsScreen(
            onDeveloperToolsClick = {},
            onDiagnosticLogsClick = {},
            onNavigateBack = {},
            onServersClick = {},
            onAppearanceClick = {},
            onClearData = {},
            onClearSessionCache = {},
            sessionCacheSizeLabel = UiText.resource(R.string.settings_session_cache_empty),
            onRefreshSessionCacheSize = {},
            version = "1.0",
            build = "1",
            hideUserDirectoryPrefix = HomeDisplayPreferences.hideUserDirectoryPrefix,
            onHideUserDirectoryPrefixChange = {},
            recentWindowHours = HomeDisplayPreferences.recentWindowHours,
            onRecentWindowHoursChange = {},
            collapseConsecutiveToolCalls =
                SessionChatDisplayPreferences.collapseConsecutiveToolCalls,
            onCollapseConsecutiveToolCallsChange = {},
            timelineStyle = SessionChatDisplayPreferences.timelineStyle,
            onTimelineStyleChange = {},
            followUpDelivery = FollowUpDelivery.Steer,
            onFollowUpDeliveryChange = {},
            supportsFollowUpDelivery = true,
            productAnalyticsConsented = false,
            onProductAnalyticsConsentedChange = {},
        )
    }
}
