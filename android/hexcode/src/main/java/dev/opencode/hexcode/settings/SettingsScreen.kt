package dev.opencode.hexcode.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.BuildConfig
import dev.opencode.hexcode.R
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.components.HexRow
import dev.opencode.hexcode.designsystem.components.HexRowTrailing
import dev.opencode.hexcode.designsystem.components.HexSectionLabel
import dev.opencode.hexcode.designsystem.components.HexToastHost
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.firstrun.PageTop
import dev.opencode.hexcode.firstrun.ServerLabel
import dev.opencode.mobile.domain.text.TitleCaseIdentifier

/**
 * Settings, the prototype's plain list. Project, Model, Reasoning and Appearance work; Start a new session, Server
 * and the three dictation rows are placeholders for now. Tapping Version 10 times unlocks the Developer section
 * ([developerSettings]), for whoever builds and tests the app.
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
    onVersion: () -> Unit,
    developerSettings: Boolean,
    onGallery: () -> Unit,
    onSpeechLab: () -> Unit,
    sampleSession: String,
    onSampleSession: () -> Unit,
    /** Debug builds only: "Drop event stream". */
    onDropStream: (() -> Unit)?,
    onHideDeveloperSettings: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var notYet by remember { mutableStateOf<HexToastMessage?>(null) }
    val notYetText = stringResource(R.string.settings_soon)
    val placeholder = { notYet = HexToastMessage(notYetText, HexToastTone.Info) }
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
                HexSectionLabel(stringResource(R.string.settings_session))
                Row(HexIcons.folder, stringResource(R.string.settings_project), prefs.project?.name, projectPath, onProject)
                Row(
                    HexIcons.refresh,
                    stringResource(R.string.settings_new_session),
                    null,
                    stringResource(R.string.settings_new_session_sub),
                    placeholder,
                )
                HexSectionLabel(stringResource(R.string.settings_agent))
                Row(HexIcons.cpu, stringResource(R.string.settings_model), modelName, null, onModel)
                Row(HexIcons.spark, stringResource(R.string.settings_reasoning), reasoningValue(prefs), null, onModel)
                HexSectionLabel(stringResource(R.string.settings_dictation))
                Row(
                    HexIcons.mic,
                    stringResource(R.string.settings_speech_model),
                    stringResource(R.string.settings_speech_model_value),
                    stringResource(R.string.settings_speech_model_sub),
                    placeholder,
                )
                Row(
                    HexIcons.book,
                    stringResource(R.string.settings_language),
                    stringResource(R.string.settings_language_english),
                    null,
                    placeholder,
                )
                Row(
                    HexIcons.pencil,
                    stringResource(R.string.settings_text_style),
                    stringResource(R.string.settings_text_style_plain),
                    null,
                    placeholder,
                )
                HexSectionLabel(stringResource(R.string.settings_app))
                Row(
                    HexIcons.sun,
                    stringResource(R.string.settings_appearance),
                    appearanceName(prefs.appearance),
                    null,
                    onAppearance,
                )
                Row(
                    HexIcons.server,
                    stringResource(R.string.settings_server),
                    server?.name,
                    server?.let { serverDetails(it) },
                    placeholder,
                )
                HexRow(
                    title = stringResource(R.string.settings_version),
                    icon = HexIcons.info,
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                    onClick = onVersion,
                )
                if (developerSettings) {
                    DeveloperSection(
                        onGallery,
                        onSpeechLab,
                        sampleSession,
                        onSampleSession,
                        onDropStream,
                        { notYet = HexToastMessage(DeveloperCopy.BUILD_COPIED, HexToastTone.Steer) },
                        onHideDeveloperSettings,
                    )
                }
            }
        }
        val changed = remember(notice) { notice?.let { HexToastMessage(it, HexToastTone.Steer) } }
        HexToastHost(
            message = notYet ?: changed,
            onDismissed = {
                notYet = null
                onNoticeShown()
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
    }
}

/** Settings › Developer, after App once unlocked. */
@Composable
private fun DeveloperSection(
    onGallery: () -> Unit,
    onSpeechLab: () -> Unit,
    sampleSession: String,
    onSampleSession: () -> Unit,
    onDropStream: (() -> Unit)?,
    onBuildCopied: () -> Unit,
    onHide: () -> Unit,
) {
    val context = LocalContext.current
    HexSectionLabel(DeveloperCopy.SECTION)
    Row(HexIcons.card, DeveloperCopy.GALLERY, null, DeveloperCopy.GALLERY_SUBTITLE, onGallery)
    Row(HexIcons.bars, DeveloperCopy.SPEECH_LAB, null, DeveloperCopy.SPEECH_LAB_SUBTITLE, onSpeechLab)
    HexRow(
        title = DeveloperCopy.SAMPLE_SESSION,
        icon = HexIcons.doc,
        value = sampleSession,
        subtitle = DeveloperCopy.SAMPLE_SESSION_SUBTITLE,
        onClick = onSampleSession,
    )
    if (onDropStream != null) {
        HexRow(
            title = DeveloperCopy.DROP_STREAM,
            icon = HexIcons.server,
            subtitle = DeveloperCopy.DROP_STREAM_SUBTITLE,
            onClick = onDropStream,
        )
    }
    HexRow(
        title = DeveloperCopy.BUILD,
        icon = HexIcons.term,
        subtitle =
            listOfNotNull(
                BuildConfig.VERSION_NAME,
                BuildConfig.BUILD_TYPE,
                BuildConfig.GIT_COMMIT.ifEmpty { null },
            ).joinToString(" · "),
        onClick = {
            val summary =
                listOfNotNull("Hex Code", BuildConfig.VERSION_NAME, "(${BuildConfig.BUILD_TYPE})", BuildConfig.GIT_COMMIT.ifEmpty { null })
            context
                .getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(DeveloperCopy.BUILD, summary.joinToString(" ")))
            // Android 13 and later confirm a copy themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onBuildCopied()
        },
    )
    HexRow(title = DeveloperCopy.HIDE, icon = HexIcons.eye, onClick = onHide)
}

/** Settings › Developer is for whoever builds and tests the app: English only, not in the string resources. */
private object DeveloperCopy {
    const val SECTION = "Developer"
    const val GALLERY = "Component gallery"
    const val GALLERY_SUBTITLE = "Every Hex component in each of its states"
    const val SPEECH_LAB = "Speech lab"
    const val SPEECH_LAB_SUBTITLE = "Download, test and compare speech models"
    const val SAMPLE_SESSION = "Sample session"
    const val SAMPLE_SESSION_SUBTITLE = "Home with a fake session, or Off for the real one. Tap for the next."
    const val DROP_STREAM = "Drop event stream"
    const val DROP_STREAM_SUBTITLE = "As if the network dropped, for 10 s. Debug builds only."
    const val BUILD = "Build"
    const val BUILD_COPIED = "Build copied"
    const val HIDE = "Hide developer settings"
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
    HexRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        value = value,
        trailing = HexRowTrailing.Chevron,
        onClick = onClick,
    )
}

/** "my-computer · OpenCode 2.2.5": the computer's own hostname and its OpenCode version, as far as known. */
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
