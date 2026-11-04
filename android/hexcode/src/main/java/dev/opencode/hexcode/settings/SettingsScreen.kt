package dev.opencode.hexcode.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.firstrun.PageTop
import dev.opencode.hexcode.firstrun.ServerLabel
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * Settings, the prototype's plain list. Project, Model, Reasoning and Appearance work; Start a new session, Server
 * and the three dictation rows are placeholders for now.
 */
@Composable
fun SettingsScreen(
    prefs: HexPrefs,
    server: ServerLabel?,
    modelName: String?,
    projectPath: String?,
    notice: String?,
    onNoticeShown: () -> Unit,
    onProject: () -> Unit,
    onModel: () -> Unit,
    onAppearance: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var notYet by remember { mutableStateOf<OpenCodeSnackbarMessage?>(null) }
    val notYetText = stringResource(R.string.settings_soon)
    val placeholder = { notYet = OpenCodeSnackbarMessage(notYetText) }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            PageTop(
                stringResource(R.string.settings_title),
                server?.let { label ->
                    label.version?.let { stringResource(R.string.settings_server_status, label.name, it) } ?: label.name
                },
                onBack,
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                OpenCodeSectionHeader(stringResource(R.string.settings_session))
                Row(OpenCodeIcons.Folder, stringResource(R.string.settings_project), prefs.project?.name, projectPath, onProject)
                Row(
                    OpenCodeIcons.Restart,
                    stringResource(R.string.settings_new_session),
                    null,
                    stringResource(R.string.settings_new_session_sub),
                    placeholder,
                )
                OpenCodeSectionHeader(stringResource(R.string.settings_agent))
                Row(OpenCodeIcons.Agent, stringResource(R.string.settings_model), modelName, null, onModel)
                Row(OpenCodeIcons.Architecture, stringResource(R.string.settings_reasoning), reasoningValue(prefs), null, onModel)
                OpenCodeSectionHeader(stringResource(R.string.settings_dictation))
                Row(
                    OpenCodeIcons.Mic,
                    stringResource(R.string.settings_speech_model),
                    stringResource(R.string.settings_speech_model_value),
                    stringResource(R.string.settings_speech_model_sub),
                    placeholder,
                )
                Row(
                    OpenCodeIcons.Description,
                    stringResource(R.string.settings_language),
                    stringResource(R.string.settings_language_english),
                    null,
                    placeholder,
                )
                Row(
                    OpenCodeIcons.Edit,
                    stringResource(R.string.settings_text_style),
                    stringResource(R.string.settings_text_style_plain),
                    null,
                    placeholder,
                )
                OpenCodeSectionHeader(stringResource(R.string.settings_app))
                Row(
                    OpenCodeIcons.Widgets,
                    stringResource(R.string.settings_appearance),
                    appearanceName(prefs.appearance),
                    null,
                    onAppearance,
                )
                Row(
                    OpenCodeIcons.Cloud,
                    stringResource(R.string.settings_server),
                    server?.name,
                    server?.let { serverDetails(it) },
                    placeholder,
                )
            }
        }
        OpenCodeSnackbarHost(
            message = notYet ?: notice?.let { OpenCodeSnackbarMessage(it) },
            onDismissed = {
                notYet = null
                onNoticeShown()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** A settings row: what it is, its current value on the right, and a chevron. */
@Composable
private fun Row(
    icon: ImageVector,
    title: String,
    value: String?,
    subtitle: String?,
    onClick: () -> Unit,
) {
    OpenCodeListItem(
        title = title,
        subtitle = subtitle,
        startDecorator = StartDecorator.Icon(icon, null),
        accessory = OpenCodeListItemAccessory.Disclosure,
        onClick = onClick,
        trailing =
            value?.let {
                {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = openCodeColor("text-base"), maxLines = 1)
                }
            },
    )
}

/** "ivans-macbook-pro · OpenCode 2.2.5": the computer's own hostname and its OpenCode version, as far as known. */
@Composable
private fun serverDetails(server: ServerLabel): String? {
    val hostname = server.hostname
    val version = server.version
    return when {
        hostname != null && version != null -> stringResource(R.string.settings_server_details, hostname, version)
        version != null -> stringResource(R.string.settings_server_version, version)
        else -> hostname
    }
}

@Composable
private fun reasoningValue(prefs: HexPrefs): String =
    prefs.model?.variant?.let(TitleCaseIdentifier::displayName)
        ?: stringResource(if (prefs.model != null) R.string.settings_reasoning_default else R.string.settings_reasoning_none)

@Composable
private fun appearanceName(appearance: Appearance): String =
    stringResource(
        when (appearance) {
            Appearance.Auto -> R.string.settings_appearance_auto
            Appearance.Light -> R.string.settings_appearance_light
            Appearance.Dark -> R.string.settings_appearance_dark
        },
    )
