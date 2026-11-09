package dev.opencode.hexcode.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonSize
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexCode
import dev.opencode.hexcode.designsystem.components.HexCodeLine
import dev.opencode.hexcode.designsystem.components.HexCodeLineKind
import dev.opencode.hexcode.designsystem.components.HexCodeText
import dev.opencode.hexcode.designsystem.components.HexLinkKind
import dev.opencode.hexcode.designsystem.components.HexLinkTile
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexSheetRequest
import dev.opencode.hexcode.designsystem.components.HexSheetSize
import dev.opencode.hexcode.designsystem.components.HexSpinner
import dev.opencode.hexcode.designsystem.components.HexStep
import dev.opencode.hexcode.designsystem.components.HexStepState
import dev.opencode.hexcode.designsystem.components.HexSteps
import dev.opencode.hexcode.designsystem.components.HexToastHost
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.firstrun.ProjectPicker
import dev.opencode.hexcode.session.ApprovalLines
import dev.opencode.hexcode.session.ArtifactSetup
import dev.opencode.hexcode.session.ArtifactsState
import dev.opencode.hexcode.session.ArtifactsUi
import dev.opencode.hexcode.session.CodeLine
import dev.opencode.hexcode.session.ConfigTarget
import dev.opencode.hexcode.session.SetupActivity
import dev.opencode.hexcode.session.SetupMode
import dev.opencode.hexcode.session.SetupStage
import dev.opencode.hexcode.session.SetupStepState

/** What the artifact sheets and the Settings row ask for ([HomeViewModel] does it). */
interface ArtifactActions {
    /** The Settings row, the hint, the browser's Turn on…: the enable sheet, or the turn-off sheet while on. */
    fun openArtifacts()

    fun sendArtifactsPrompt()

    /** The sheet hides; a running flow keeps going. */
    fun hideArtifacts()

    /** Cancel, Done or Close. */
    fun closeArtifacts()

    fun allowArtifactsEdit()

    fun rejectArtifactsEdit()

    fun restoreArtifactsConfig()

    fun retryArtifacts()

    /** The suggestion chip: "Make a small page that previews " into the draft. */
    fun artifactsSuggestion(text: String)

    fun artifactsHintDone()
}

/**
 * The artifact sheets (app-plan.md, Artifacts (M8); the prototype's variant A): one `HexSheet` that explains, shows the
 * exact prompt and where it goes, then ticks through Sent → Agent working → Approval → Checking → On; the setup
 * session's approval (Allow once and Reject only); the agent's replies; and the notices that say what happened while
 * the sheet was hidden. Put it last in a full-screen `Box`, over Settings or home. [computer] is the computer's name;
 * [home] its home folder, for shorter paths.
 */
@Composable
fun BoxScope.ArtifactSheets(
    ui: ArtifactsUi,
    actions: ArtifactActions,
    computer: String,
    home: String?,
    /** Home shows the one-time hint; Settings doesn't. */
    showHint: Boolean = false,
    /** Where notices sit: under the screen's top bar. */
    noticeTop: androidx.compose.ui.unit.Dp = 68.dp,
) {
    val flow = ui.setup
    var shown by remember { mutableStateOf<ArtifactSetup?>(null) }
    if (flow != null) shown = flow
    val setup = shown
    var replies by remember { mutableStateOf(false) }
    // The approval takes the step sheet's place until it's answered or hidden.
    var approvalHidden by remember { mutableStateOf<String?>(null) }
    val approval = flow?.approval
    val visible = flow != null && !flow.hidden
    val approvalOpen = visible && approval != null && approvalHidden != approval.id && !replies
    val stepsOpen = visible && !approvalOpen && !replies
    val path = { target: ConfigTarget? -> shortPath((target as? ConfigTarget.File)?.path, home) }
    if (setup != null) {
        HexSheet(
            visible = stepsOpen,
            onDismiss = { if (setup.stage == SetupStage.Running) actions.hideArtifacts() else actions.closeArtifacts() },
            title = sheetTitle(setup),
            request = HexSheetRequest(stringResource(R.string.needs_hide), actions::hideArtifacts),
            loading = setup.stage == SetupStage.Reading,
            footer = { SheetButtons(setup, ui.state, actions) },
        ) {
            if (setup.stage != SetupStage.Reading) {
                SheetBody(
                    setup,
                    ui.state,
                    computer,
                    path(setup.target),
                    actions,
                    onReview = { approvalHidden = null },
                    onReplies = { replies = true },
                )
            }
        }
        val item = approval ?: setup.approval
        HexSheet(
            visible = approvalOpen,
            onDismiss = { approvalHidden = approval?.id },
            title =
                stringResource(
                    R.string.artifacts_approval_title,
                    (setup.target as? ConfigTarget.File)?.name
                        ?: item
                            ?.approval
                            ?.files
                            ?.firstOrNull()
                            ?.path
                            ?.substringAfterLast('/')
                            .orEmpty(),
                ),
            request =
                HexSheetRequest(
                    stringResource(R.string.needs_hide),
                    { approvalHidden = approval?.id },
                    count = stringResource(R.string.artifacts_flow_setup),
                ),
            amberEdge = true,
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HexButton(
                        onClick = actions::rejectArtifactsEdit,
                        text = stringResource(R.string.artifacts_approval_reject),
                        style = HexButtonStyle.Danger,
                        size = HexButtonSize.Regular,
                        loading = setup.answering == false,
                    )
                    Spacer(Modifier.weight(1f))
                    HexButton(
                        onClick = actions::allowArtifactsEdit,
                        text = stringResource(R.string.needs_approval_once),
                        style = HexButtonStyle.Primary,
                        size = HexButtonSize.Regular,
                        loading = setup.answering == true,
                    )
                }
            },
        ) {
            if (item != null) ApprovalBody(item.approval, path(setup.target))
        }
        HexSheet(
            visible = visible && replies,
            onDismiss = { replies = false },
            title = stringResource(R.string.artifacts_flow_setup),
            trailing = HexSheetAction(stringResource(R.string.common_done)) { replies = false },
            size = HexSheetSize.Medium,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (setup.replies.isEmpty()) {
                    Text(stringResource(R.string.artifacts_flow_replies_none), style = HexTheme.type.body, color = HexTheme.colors.text3)
                }
                setup.replies.forEach { reply ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.artifacts_flow_agent), style = HexTheme.type.monoSmall, color = HexTheme.colors.text3)
                        HexCodeText(summaryText(reply), style = HexTheme.type.body, color = HexTheme.colors.text1)
                    }
                }
            }
        }
    }
    Notices(ui, actions, computer, path(flow?.target), showHint, noticeTop)
}

/** The sheet's title: Turn on artifacts, then Turning on artifacts… while it runs. */
@Composable
private fun sheetTitle(setup: ArtifactSetup): String {
    val on = setup.mode == SetupMode.On
    return when {
        setup.stage == SetupStage.Explain || setup.stage == SetupStage.Reading || setup.stage is SetupStage.Blocked ->
            stringResource(if (on) R.string.artifacts_sheet_turn_on else R.string.artifacts_sheet_turn_off)
        setup.stage == SetupStage.Running ->
            stringResource(if (on) R.string.artifacts_flow_turning_on else R.string.artifacts_flow_turning_off)
        else -> stringResource(if (on) R.string.artifacts_flow_turning_on else R.string.artifacts_flow_turning_off).removeSuffix("…")
    }
}

@Composable
private fun SheetBody(
    setup: ArtifactSetup,
    state: ArtifactsState,
    computer: String,
    path: String,
    actions: ArtifactActions,
    onReview: () -> Unit,
    onReplies: () -> Unit,
) {
    val colors = HexTheme.colors
    val on = setup.mode == SetupMode.On
    val stage = setup.stage
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            stage == SetupStage.Explain -> {
                Intro(on, computer)
                WillEdit(setup.target, path)
                PromptBox(setup.prompt.orEmpty())
                WhereItGoes(setup.reused)
            }
            stage is SetupStage.Blocked -> {
                Intro(on, computer)
                // A person added the line meanwhile: it's on.
                if (on && state == ArtifactsState.On) {
                    Result(HexIcons.check, Tone.Green, stringResource(R.string.artifacts_sheet_now_on), null)
                } else {
                    val why =
                        when (stage.why) {
                            SetupStage.Blocked.Why.Env -> stringResource(R.string.artifacts_sheet_env)
                            SetupStage.Blocked.Why.Broken -> stringResource(R.string.artifacts_sheet_broken, path)
                            SetupStage.Blocked.Why.NoConfig -> stringResource(R.string.artifacts_sheet_no_config, computer)
                            SetupStage.Blocked.Why.Unavailable -> stringResource(R.string.settings_artifacts_unavailable_sub)
                        }
                    Text(why, style = HexTheme.type.body, color = colors.text2)
                    if (stage.why != SetupStage.Blocked.Why.Unavailable) {
                        Fallback(setup, computer, path.ifEmpty { "~/.config/opencode/opencode.json" })
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(HexIcons.mark, null, tint = colors.text3, modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.artifacts_flow_sub), style = HexTheme.type.caption, color = colors.text3)
                }
                HexSteps(steps(setup, computer, path, onReview))
                Text(
                    stringResource(R.string.artifacts_flow_replies) + " ›",
                    style = HexTheme.type.callout,
                    color = colors.text2,
                    modifier =
                        Modifier
                            .padding(start = 30.dp)
                            .clickable(role = Role.Button, onClick = onReplies)
                            .padding(vertical = 4.dp),
                )
                Outcome(setup, computer, path, actions)
            }
        }
    }
}

/** The tiny example tile and what artifacts are; or, turning off, what that means. */
@Composable
private fun Intro(
    on: Boolean,
    computer: String,
) {
    val colors = HexTheme.colors
    if (!on) {
        Text(stringResource(R.string.artifacts_sheet_intro_off), style = HexTheme.type.body, color = colors.text2)
        return
    }
    val intro = stringResource(R.string.artifacts_sheet_intro, computer)
    val word = stringResource(R.string.settings_artifacts)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        HexLinkTile(
            label = stringResource(R.string.artifacts_sheet_example),
            host = stringResource(R.string.home_tile_artifact),
            kind = HexLinkKind.Artifact,
            accessibilityLabel = "",
            onClick = {},
            modifier = Modifier.width(132.dp),
        )
        Text(
            buildAnnotatedString {
                // "Artifacts" leads in text1, as the prototype's bold word.
                if (intro.startsWith(word)) {
                    withStyle(SpanStyle(color = colors.text1, fontWeight = FontWeight.Medium)) { append(word) }
                    append(intro.removePrefix(word))
                } else {
                    append(intro)
                }
            },
            style = HexTheme.type.callout,
            color = colors.text2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "Will edit <file>" and why that file (`.tgt.will`). */
@Composable
private fun WillEdit(
    target: ConfigTarget?,
    path: String,
) {
    val colors = HexTheme.colors
    val file = target as? ConfigTarget.File ?: return
    Panel {
        Text(stringResource(R.string.artifacts_sheet_will_edit), style = HexTheme.type.caption, color = colors.text3)
        Text(path, style = HexTheme.type.mono, color = colors.text1)
        Text(
            stringResource(
                when {
                    !file.global -> R.string.artifacts_sheet_will_edit_project
                    file.hasBlock -> R.string.artifacts_sheet_will_edit_global_block
                    else -> R.string.artifacts_sheet_will_edit_global
                },
            ),
            style = HexTheme.type.caption,
            color = colors.text3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The exact prompt (`.pbox`): "Sent to the agent, as you · exact text". */
@Composable
private fun PromptBox(prompt: String) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = 13.dp, end = 13.dp, top = 11.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            Text(
                stringResource(R.string.artifacts_sheet_prompt),
                style = HexTheme.type.monoSmall,
                color = colors.text2,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.artifacts_sheet_prompt_exact), style = HexTheme.type.monoSmall, color = colors.text3)
        }
        HexCodeText(summaryText(prompt), style = HexTheme.type.callout, color = colors.text1)
    }
}

/** Where the prompt goes (`.where.wa`): the setup session, New or Reused. */
@Composable
private fun WhereItGoes(reused: Boolean) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.panel, shape)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .border(1.dp, colors.line2, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(HexIcons.mark, null, tint = colors.text1, modifier = Modifier.size(13.dp)) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.artifacts_sheet_where), style = HexTheme.type.callout, color = colors.text1)
            Text(stringResource(R.string.artifacts_sheet_where_sub), style = HexTheme.type.caption, color = colors.text3)
        }
        Text(
            stringResource(if (reused) R.string.artifacts_sheet_reused else R.string.artifacts_sheet_new),
            style = HexTheme.type.caption,
            color = if (reused) colors.text3 else colors.green,
        )
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel, shape)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        content = content,
    )
}

/** The step list for the flow's state. */
@Composable
private fun steps(
    setup: ArtifactSetup,
    computer: String,
    path: String,
    onReview: () -> Unit,
): List<HexStep> {
    val on = setup.mode == SetupMode.On
    val s = setup.steps
    val list = mutableListOf<HexStep>()
    list += HexStep(state(s.sent), stringResource(R.string.artifacts_flow_sent), stringResource(R.string.artifacts_flow_sent_sub))
    val working = s.work == SetupStepState.Doing || s.work == SetupStepState.Dimmed
    list +=
        HexStep(
            state(s.work),
            stringResource(
                when (s.work) {
                    SetupStepState.Done -> R.string.artifacts_flow_changed
                    SetupStepState.Failed -> R.string.artifacts_flow_not_changed
                    else -> R.string.artifacts_flow_working
                },
            ),
            if (working || s.work == SetupStepState.Todo) live(setup) else path,
            live = working,
        )
    s.approval?.let { approval ->
        list +=
            HexStep(
                state(approval),
                stringResource(
                    when (approval) {
                        SetupStepState.Done -> R.string.artifacts_flow_allowed
                        SetupStepState.Failed -> R.string.artifacts_flow_rejected
                        else -> R.string.artifacts_flow_approval
                    },
                ),
                stringResource(R.string.artifacts_approval_title, path.substringAfterLast('/')),
                action =
                    if (approval == SetupStepState.Warn) {
                        HexSheetAction(stringResource(R.string.artifacts_flow_review), onReview)
                    } else {
                        null
                    },
            )
    }
    list +=
        HexStep(
            state(s.check),
            when (s.check) {
                SetupStepState.Done -> stringResource(R.string.artifacts_flow_answers, computer)
                SetupStepState.Warn ->
                    stringResource(if (on) R.string.artifacts_flow_still_disabled else R.string.artifacts_flow_still_on, computer)
                else -> stringResource(R.string.artifacts_flow_checking, computer)
            },
            when (s.check) {
                SetupStepState.Doing -> stringResource(R.string.artifacts_flow_checking_every)
                SetupStepState.Todo -> stringResource(R.string.artifacts_flow_checking_sub)
                else -> null
            },
            live = s.check == SetupStepState.Doing,
        )
    list += HexStep(state(s.end), stringResource(if (on) R.string.artifacts_sheet_now_on else R.string.artifacts_flow_now_off))
    return list
}

private fun state(step: SetupStepState): HexStepState =
    when (step) {
        SetupStepState.Todo -> HexStepState.Todo
        SetupStepState.Doing -> HexStepState.Doing
        SetupStepState.Dimmed -> HexStepState.Dimmed
        SetupStepState.Warn -> HexStepState.Warn
        SetupStepState.Done -> HexStepState.Done
        SetupStepState.Failed -> HexStepState.Failed
    }

/** The agent's live line, from its tool events. */
@Composable
private fun live(setup: ArtifactSetup): String {
    if (setup.approval != null) return stringResource(R.string.artifacts_flow_waiting_approval)
    return when (val activity = setup.activity) {
        null -> stringResource(R.string.artifacts_flow_waiting)
        SetupActivity.Thinking -> stringResource(R.string.artifacts_flow_thinking)
        is SetupActivity.Reading -> stringResource(R.string.artifacts_flow_reading, activity.path.substringAfterLast('/'))
        is SetupActivity.Editing -> stringResource(R.string.artifacts_flow_editing, activity.path.substringAfterLast('/'))
        is SetupActivity.Tool -> stringResource(R.string.artifacts_flow_tool, activity.name)
    }
}

/** What happened, under the steps: On, Off, the fallback, or the broken config with Restore. */
@Composable
private fun Outcome(
    setup: ArtifactSetup,
    computer: String,
    path: String,
    actions: ArtifactActions,
) {
    val on = setup.mode == SetupMode.On
    when (val stage = setup.stage) {
        SetupStage.Done -> {
            Result(
                HexIcons.check,
                Tone.Green,
                stringResource(if (on) R.string.artifacts_sheet_now_on else R.string.artifacts_flow_now_off),
                stringResource(if (on) R.string.artifacts_flow_on_body else R.string.artifacts_flow_off_body),
                rule = stringResource(if (on) R.string.artifacts_flow_on_rule else R.string.artifacts_flow_off_rule),
            )
            if (on) Suggestion(actions)
        }
        is SetupStage.Fallback -> {
            when (stage.why) {
                SetupStage.Fallback.Why.Rejected ->
                    Result(
                        HexIcons.x,
                        Tone.Grey,
                        stringResource(R.string.artifacts_flow_rejected_title),
                        stringResource(R.string.artifacts_flow_rejected_body),
                    )
                SetupStage.Fallback.Why.Failed ->
                    Result(
                        HexIcons.x,
                        Tone.Red,
                        stringResource(R.string.artifacts_flow_not_changed),
                        stage.detail?.let { stringResource(R.string.artifacts_flow_failed_body, it) },
                    )
                SetupStage.Fallback.Why.FreeLimit ->
                    Result(HexIcons.x, Tone.Red, stringResource(R.string.artifacts_flow_couldnt_run), stage.detail)
                SetupStage.Fallback.Why.TimedOut ->
                    Result(
                        HexIcons.alert,
                        Tone.Amber,
                        stringResource(R.string.artifacts_flow_timed_out),
                        stringResource(R.string.artifacts_flow_timed_out_body),
                    )
                SetupStage.Fallback.Why.StillOff ->
                    Result(
                        HexIcons.alert,
                        Tone.Amber,
                        stringResource(if (on) R.string.artifacts_flow_still_off else R.string.artifacts_flow_still_on_title, computer),
                        if (on) stringResource(R.string.artifacts_flow_still_off_body) else null,
                    )
                SetupStage.Fallback.Why.Restored ->
                    Result(
                        HexIcons.check,
                        Tone.Grey,
                        stringResource(R.string.artifacts_flow_restored),
                        stringResource(R.string.artifacts_flow_restored_body, path),
                    )
            }
            Fallback(setup, computer, path)
        }
        is SetupStage.Damaged -> {
            val damage = stage.damage
            Result(
                HexIcons.alert,
                Tone.Amber,
                stringResource(R.string.artifacts_broken_title),
                if (damage.unreadable) {
                    stringResource(R.string.artifacts_broken_unreadable, path)
                } else {
                    stringResource(R.string.artifacts_broken_missing, path, damage.missing.joinToString(", "))
                },
            )
            Text(stringResource(R.string.artifacts_restore_body), style = HexTheme.type.caption, color = HexTheme.colors.text3)
            if (setup.restoreFailed) {
                Text(
                    stringResource(R.string.artifacts_restore_failed) + ". " + stringResource(R.string.artifacts_restore_failed_sub),
                    style = HexTheme.type.caption,
                    color = HexTheme.colors.red,
                )
            }
        }
        else -> Unit
    }
}

private enum class Tone { Green, Amber, Red, Grey }

/** A result (`.res`): a round mark, a title, a line, and the rule line. */
@Composable
private fun Result(
    icon: ImageVector,
    tone: Tone,
    title: String,
    body: String?,
    rule: String? = null,
) {
    val colors = HexTheme.colors
    val (tint, fill) =
        when (tone) {
            Tone.Green -> colors.green to colors.green.copy(alpha = 0.13f)
            Tone.Amber -> colors.amber to colors.amberSoft
            Tone.Red -> colors.red to colors.redSoft
            Tone.Grey -> colors.text2 to colors.tint(6f)
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(title, style = HexTheme.type.headline, color = colors.text1, modifier = Modifier.semantics { heading() })
        body?.let { HexCodeText(summaryText(it), style = HexTheme.type.body, color = colors.text2) }
        rule?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(HexIcons.spark, null, tint = colors.green, modifier = Modifier.padding(top = 2.dp).size(13.dp))
                Text(it, style = HexTheme.type.caption, color = colors.text3)
            }
        }
    }
}

/** The suggestion chip once on: "Make a small page that previews …" goes into the draft. */
@Composable
private fun Suggestion(actions: ArtifactActions) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    val text = stringResource(R.string.artifacts_flow_on_suggestion)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = HexTheme.size.touch)
                .background(colors.panel, shape)
                .border(1.dp, colors.line, shape)
                .clickable(role = Role.Button) { actions.artifactsSuggestion("$text ") }
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(28.dp).background(colors.tint(8f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(HexIcons.mic, null, tint = colors.text2, modifier = Modifier.size(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("“$text …”", style = HexTheme.type.callout, color = colors.text1)
            Text(stringResource(R.string.artifacts_flow_on_suggestion_sub), style = HexTheme.type.caption, color = colors.text3)
        }
        Icon(HexIcons.chevR, null, tint = colors.text3, modifier = Modifier.size(14.dp))
    }
}

/** The line to add by hand, with its file and Copy; the 2 s check keeps watching while the sheet is open. */
@Composable
private fun Fallback(
    setup: ArtifactSetup,
    computer: String,
    path: String,
) {
    val colors = HexTheme.colors
    val context = LocalContext.current
    val on = setup.mode == SetupMode.On
    val line = setup.target?.line ?: dev.opencode.hexcode.session.ArtifactPrompts.BLOCK_LINE
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(
            stringResource(if (on) R.string.artifacts_flow_fallback else R.string.artifacts_flow_fallback_off, computer, path),
            style = HexTheme.type.caption,
            color = colors.text2,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HexCode(listOf(HexCodeLine(line, HexCodeLineKind.Plain)), Modifier.weight(1f))
            HexButton(
                onClick = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("artifacts", line))
                    copied = true
                },
                text = stringResource(if (copied) R.string.artifacts_sheet_copied else R.string.artifacts_sheet_copy),
                icon = if (copied) HexIcons.check else HexIcons.copy,
            )
        }
        if (setup.watching && on) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HexSpinner(size = 13.dp)
                Text(stringResource(R.string.artifacts_flow_fallback_watching), style = HexTheme.type.caption, color = colors.text3)
            }
        }
    }
}

/** Cancel / Send before; Done once done; Close / Try again after a fallback; Close / Restore for a broken config. */
@Composable
private fun ColumnScope.SheetButtons(
    setup: ArtifactSetup,
    state: ArtifactsState,
    actions: ArtifactActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (val stage = setup.stage) {
            SetupStage.Reading, SetupStage.Explain -> {
                HexButton(onClick = actions::closeArtifacts, text = stringResource(R.string.common_cancel), size = HexButtonSize.Regular)
                Spacer(Modifier.weight(1f))
                HexButton(
                    onClick = actions::sendArtifactsPrompt,
                    text = stringResource(R.string.artifacts_sheet_send),
                    style = HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                    enabled = stage == SetupStage.Explain,
                    loading = setup.sending,
                )
            }
            SetupStage.Running -> Unit
            SetupStage.Done -> {
                Spacer(Modifier.weight(1f))
                HexButton(
                    onClick = actions::closeArtifacts,
                    text = stringResource(R.string.common_done),
                    style = HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                )
            }
            is SetupStage.Fallback -> {
                HexButton(onClick = actions::closeArtifacts, text = stringResource(R.string.common_close), size = HexButtonSize.Regular)
                Spacer(Modifier.weight(1f))
                HexButton(
                    onClick = actions::retryArtifacts,
                    text = stringResource(R.string.artifacts_flow_try_again),
                    style = HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                )
            }
            is SetupStage.Damaged -> {
                HexButton(onClick = actions::closeArtifacts, text = stringResource(R.string.common_close), size = HexButtonSize.Regular)
                Spacer(Modifier.weight(1f))
                HexButton(
                    onClick = actions::restoreArtifactsConfig,
                    text = stringResource(R.string.artifacts_restore),
                    style = HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                    loading = setup.restoring,
                )
            }
            is SetupStage.Blocked -> {
                Spacer(Modifier.weight(1f))
                HexButton(
                    onClick = actions::closeArtifacts,
                    text = stringResource(if (state == ArtifactsState.On) R.string.common_done else R.string.common_close),
                    style = HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                )
            }
        }
    }
}

/** The approval's body: the full path, the diff, and where it comes from. */
@Composable
private fun ApprovalBody(
    approval: dev.opencode.hexcode.session.ApprovalRequest?,
    path: String,
) {
    val colors = HexTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(path, style = HexTheme.type.mono, color = colors.text2)
        if (approval != null) {
            // The diff without the file's own (relative, in a project) path: the absolute one is above.
            val lines = ApprovalLines.of(approval).let { all -> if (approval.files.isNotEmpty()) all.drop(1) else all }
            if (lines.isNotEmpty()) HexCode(lines.map { codeLine(it) })
            val file = approval.files.firstOrNull()
            if (file != null && (file.additions > 0 || file.deletions > 0)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.green)) { append("+${file.additions}") }
                        append(" ")
                        withStyle(SpanStyle(color = colors.red)) { append("−${file.deletions}") }
                    },
                    style = HexTheme.type.monoSmall,
                )
            }
        }
        Text(stringResource(R.string.artifacts_approval_from), style = HexTheme.type.caption, color = colors.text3)
    }
}

@Composable
private fun codeLine(line: CodeLine): HexCodeLine =
    when (line.kind) {
        CodeLine.Kind.Plain -> HexCodeLine(line.text)
        CodeLine.Kind.Added -> HexCodeLine(line.text, HexCodeLineKind.Added)
        CodeLine.Kind.Removed -> HexCodeLine(line.text, HexCodeLineKind.Removed)
        CodeLine.Kind.More -> HexCodeLine(stringResource(R.string.needs_approval_more_lines, line.text.toInt()), HexCodeLineKind.More)
    }

/**
 * What happened while the sheet was hidden (approval needed, on, off, the agent didn't change it, a broken config), and
 * on home the one-time hint.
 */
@Composable
private fun BoxScope.Notices(
    ui: ArtifactsUi,
    actions: ArtifactActions,
    computer: String,
    path: String,
    showHint: Boolean,
    top: androidx.compose.ui.unit.Dp,
) {
    val flow = ui.setup
    var notice by remember { mutableStateOf<HexToastMessage?>(null) }
    var action by remember { mutableStateOf<(() -> Unit)?>(null) }
    val approvalTitle = stringResource(R.string.artifacts_notice_approval)
    val approvalSub = stringResource(R.string.artifacts_notice_approval_sub, path.substringAfterLast('/'))
    val review = stringResource(R.string.artifacts_notice_review)
    val onTitle = stringResource(R.string.artifacts_sheet_now_on)
    val onSub = stringResource(R.string.artifacts_notice_on_sub)
    val offTitle = stringResource(R.string.artifacts_flow_now_off)
    val failedTitle = stringResource(R.string.artifacts_flow_not_changed)
    val openSub = stringResource(R.string.artifacts_notice_open_sub)
    val open = stringResource(R.string.artifacts_notice_open)
    val broken = stringResource(R.string.artifacts_broken_title)
    val hintTitle = stringResource(R.string.home_hint_artifacts)
    val hintAction = stringResource(R.string.home_hint_artifacts_sub)
    val hidden = flow?.hidden == true
    val kind =
        when {
            !hidden || flow == null -> null
            flow.approval != null -> "approval:${flow.approval.id}"
            flow.stage == SetupStage.Done -> "done"
            flow.stage is SetupStage.Damaged -> "damaged"
            flow.stage is SetupStage.Fallback -> "fallback"
            else -> null
        }
    LaunchedEffect(kind) {
        val shown = flow ?: return@LaunchedEffect
        when {
            kind == null -> Unit
            kind.startsWith("approval") -> {
                notice = HexToastMessage(approvalTitle, HexToastTone.Info, approvalSub, actionLabel = review, durationMs = 6_000)
                action = actions::openArtifacts
            }
            kind == "done" -> {
                val turnedOn = shown.mode == SetupMode.On
                notice =
                    HexToastMessage(
                        if (turnedOn) onTitle else offTitle,
                        HexToastTone.Steer,
                        if (turnedOn) onSub else null,
                        durationMs = 3_600,
                    )
                action = null
                // Nothing more to show: Settings reads On or Off now.
                actions.closeArtifacts()
            }
            kind == "damaged" -> {
                notice = HexToastMessage(broken, HexToastTone.Stop, actionLabel = open, durationMs = 8_000)
                action = actions::openArtifacts
            }
            kind == "fallback" -> {
                notice = HexToastMessage(failedTitle, HexToastTone.Info, openSub, actionLabel = open, durationMs = 6_000)
                action = actions::openArtifacts
            }
        }
    }
    LaunchedEffect(showHint && ui.hint) {
        if (showHint && ui.hint) {
            notice = HexToastMessage(hintTitle, HexToastTone.Artifact, actionLabel = hintAction, durationMs = 8_000)
            action = {
                actions.artifactsHintDone()
                actions.openArtifacts()
            }
        }
    }
    HexToastHost(
        message = notice,
        onDismissed = {
            if (notice?.title == hintTitle) actions.artifactsHintDone()
            notice = null
        },
        onAction = { action?.invoke() },
        modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
    )
}

/** [path] with the computer's home as `~`, when it's known. */
private fun shortPath(
    path: String?,
    home: String?,
): String = path?.let { if (home.isNullOrBlank()) it else ProjectPicker.shortPath(it, home) }.orEmpty()
