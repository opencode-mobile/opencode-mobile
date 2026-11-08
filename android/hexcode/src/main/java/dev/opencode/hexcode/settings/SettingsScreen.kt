package dev.opencode.hexcode.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.BuildConfig
import dev.opencode.hexcode.R
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.app.ServerAddresses
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexBadge
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonSize
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexConfirmSheet
import dev.opencode.hexcode.designsystem.components.HexRow
import dev.opencode.hexcode.designsystem.components.HexRowTrailing
import dev.opencode.hexcode.designsystem.components.HexSectionLabel
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexToastHost
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.firstrun.PageTop
import dev.opencode.hexcode.firstrun.ServerLabel
import dev.opencode.hexcode.session.NewSessionOutcome
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import kotlinx.coroutines.launch

/** What the Start a new session confirmation says about today's session. */
data class NewSessionInfo(
    val project: String,
    /** The agent works there: it carries on. */
    val busy: Boolean,
    /** Messages waiting there still send there. */
    val waiting: Int,
)

/**
 * Settings, the prototype's plain list (app-plan.md, Settings and finish (M6)). Project, Model, Reasoning, Appearance,
 * Start a new session and Server work; the three dictation rows are placeholders for now. Version does nothing when
 * tapped; the Developer section always follows it, for whoever builds and tests the app.
 */
@Composable
fun SettingsScreen(
    prefs: HexPrefs,
    server: ServerLabel?,
    modelName: String?,
    projectPath: String?,
    notice: HexToastMessage?,
    onNoticeShown: () -> Unit,
    onProject: () -> Unit,
    onModel: () -> Unit,
    onAppearance: () -> Unit,
    onGallery: () -> Unit,
    onSpeechLab: () -> Unit,
    sampleSession: String,
    onSampleSession: () -> Unit,
    /** Debug builds only: "Drop event stream". */
    onDropStream: (() -> Unit)?,
    onBack: () -> Unit,
    /** Today's session, for Start a new session's confirmation; null while it isn't open. */
    newSession: NewSessionInfo? = null,
    /** Start a new session › Start new session; Settings closes unless creating it failed. */
    onStartNewSession: suspend () -> NewSessionOutcome = { NewSessionOutcome.Failed },
    /** Server details' addresses, the one in use first. */
    addresses: ServerAddresses? = null,
    onPairAgain: () -> Unit = {},
    /** Forget this computer, after its confirmation. */
    onForget: suspend () -> Unit = {},
) {
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    var working by remember { mutableStateOf(false) }
    BackHandler(enabled = sheet == null, onBack = onBack)
    val scope = rememberCoroutineScope()
    var notYet by remember { mutableStateOf<HexToastMessage?>(null) }
    val notYetText = stringResource(R.string.settings_soon)
    val soon = stringResource(R.string.a11y_soon)
    val placeholder = { notYet = HexToastMessage(notYetText, HexToastTone.Info) }
    val failedTitle = stringResource(R.string.settings_new_session_failed)
    val failedDetail = stringResource(R.string.settings_new_session_failed_sub)
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().then(if (sheet != null) Modifier.clearAndSetSemantics {} else Modifier)) {
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
                    { if (newSession != null) sheet = SettingsSheet.NewSession },
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
                    soon,
                )
                Row(
                    HexIcons.book,
                    stringResource(R.string.settings_language),
                    stringResource(R.string.settings_language_english),
                    null,
                    placeholder,
                    soon,
                )
                Row(
                    HexIcons.pencil,
                    stringResource(R.string.settings_text_style),
                    stringResource(R.string.settings_text_style_plain),
                    null,
                    placeholder,
                    soon,
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
                    { if (server != null) sheet = SettingsSheet.Server },
                )
                HexRow(
                    title = stringResource(R.string.settings_version),
                    icon = HexIcons.info,
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                )
                DeveloperSection(
                    onGallery,
                    onSpeechLab,
                    sampleSession,
                    onSampleSession,
                    onDropStream,
                    { notYet = HexToastMessage(DeveloperCopy.BUILD_COPIED, HexToastTone.Steer) },
                )
            }
        }
        // Start a new session (app-plan.md, Settings and finish (M6) › Start a new session).
        val info = newSession
        HexConfirmSheet(
            visible = sheet == SettingsSheet.NewSession && info != null,
            title = stringResource(R.string.settings_new_session_title),
            paragraphs =
                listOfNotNull(
                    stringResource(R.string.settings_new_session_body, info?.project.orEmpty()),
                    if (info?.busy == true) stringResource(R.string.settings_new_session_busy) else null,
                    info?.waiting?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.settings_new_session_waiting, it, it) },
                ),
            cancelLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.settings_new_session_start),
            onCancel = { sheet = null },
            onConfirm = {
                if (!working) {
                    working = true
                    scope.launch {
                        val outcome = onStartNewSession()
                        working = false
                        if (outcome == NewSessionOutcome.Failed) {
                            notYet = HexToastMessage(failedTitle, HexToastTone.Stop, failedDetail, durationMs = 3_200)
                        } else {
                            sheet = null
                            onBack()
                        }
                    }
                }
            },
            loading = working && sheet == SettingsSheet.NewSession,
        )
        ServerSheet(
            visible = sheet == SettingsSheet.Server,
            server = server,
            addresses = addresses,
            onDone = { sheet = null },
            onPairAgain = {
                sheet = null
                onPairAgain()
            },
            onForget = { sheet = SettingsSheet.Forget },
        )
        HexConfirmSheet(
            visible = sheet == SettingsSheet.Forget,
            title = stringResource(R.string.settings_forget_title),
            paragraphs = listOf(stringResource(R.string.settings_forget_message)),
            cancelLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.settings_forget_confirm),
            onCancel = { sheet = SettingsSheet.Server },
            onConfirm = {
                if (!working) {
                    working = true
                    scope.launch {
                        onForget()
                        working = false
                        sheet = null
                    }
                }
            },
            danger = true,
            loading = working && sheet == SettingsSheet.Forget,
        )
        HexToastHost(
            message = notYet ?: notice,
            onDismissed = {
                notYet = null
                onNoticeShown()
            },
            // 12 dp under the page's top bar (4 dp above it, 56 dp tall), so it never covers or touches the bar.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
        )
    }
}

/** The sheet open over Settings. */
private enum class SettingsSheet { NewSession, Server, Forget }

/**
 * Server details (app-plan.md, Settings and finish (M6) › Server): the name, "<hostname> · OpenCode <version>", every
 * address from the pairing code with the one in use first and badged, then Pair again and Forget this computer.
 */
@Composable
private fun BoxScope.ServerSheet(
    visible: Boolean,
    server: ServerLabel?,
    addresses: ServerAddresses?,
    onDone: () -> Unit,
    onPairAgain: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = HexTheme.colors
    HexSheet(
        visible = visible,
        onDismiss = onDone,
        title = stringResource(R.string.server_title),
        trailing = HexSheetAction(stringResource(R.string.common_done), onDone),
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HexButton(
                    onClick = onPairAgain,
                    text = stringResource(R.string.server_pair_again),
                    icon = HexIcons.scan,
                    size = HexButtonSize.Regular,
                    modifier = Modifier.fillMaxWidth(),
                )
                HexButton(
                    onClick = onForget,
                    text = stringResource(R.string.settings_forget_button),
                    style = HexButtonStyle.Danger,
                    size = HexButtonSize.Regular,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        if (server != null) {
            Text(server.name, style = HexTheme.type.pageTitle, color = colors.text1, modifier = Modifier.padding(horizontal = 2.dp))
            serverDetails(server)?.let {
                Text(it, style = HexTheme.type.caption, color = colors.text2, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            }
        }
        val all = addresses?.all.orEmpty()
        if (all.isNotEmpty()) {
            Text(
                stringResource(R.string.server_addresses).uppercase(),
                style = HexTheme.type.sectionLabel,
                color = colors.text3,
                modifier = Modifier.padding(start = 2.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
            )
            all.forEach { address ->
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = 32.dp,
                            ).padding(horizontal = 2.dp)
                            .semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        address,
                        style = HexTheme.type.mono,
                        color = colors.text1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (address == addresses?.inUse) HexBadge(stringResource(R.string.server_in_use))
                }
            }
        }
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
}

/** A settings row: what it is, its current value on the right, and a chevron; [note] for a screen reader. */
@Composable
private fun Row(
    icon: ImageVector,
    title: String,
    value: String?,
    subtitle: String?,
    onClick: () -> Unit,
    note: String? = null,
) {
    HexRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        value = value,
        trailing = HexRowTrailing.Chevron,
        onClick = onClick,
        accessibilityNote = note,
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
