package dev.opencode.hexcode.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexDetailRow
import dev.opencode.hexcode.designsystem.components.HexDetailsSection
import dev.opencode.hexcode.designsystem.components.HexDialSize
import dev.opencode.hexcode.designsystem.components.HexDot
import dev.opencode.hexcode.designsystem.components.HexDotTone
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexSpeechDial
import dev.opencode.voiceengine.VoiceEngine
import java.util.Locale

/**
 * The speech model's details (app-plan.md, Speech line and dial › Details sheet): `HexSheet` "Speech model" with Done,
 * fitted to its content and scrolling when long, updating live while open. The dial at 60 pt with what it's doing,
 * the four phases, a status line when one applies (the connection dropped, it failed, takes wait), then what's on this
 * phone and the pause detector. From the speech line, the draft's label line and Settings › Speech model.
 */
@Composable
internal fun BoxScope.SpeechModelSheet(
    visible: Boolean,
    speech: SpeechStatus,
    model: SpeechModelInfo?,
    draft: Draft,
    onDone: () -> Unit,
    onTryNow: () -> Unit,
    onTryAgain: () -> Unit,
) {
    HexSheet(
        visible = visible,
        onDismiss = onDone,
        title = stringResource(R.string.speech_sheet_title),
        trailing = HexSheetAction(stringResource(R.string.common_done), onDone),
    ) {
        Hero(speech, model)
        Phases(SpeechPhase.of(speech, stopped = model?.stopped != null))
        StatusLines(speech, model, draft, onTryNow, onTryAgain)
        if (model != null) {
            HexDetailsSection(stringResource(R.string.speech_on_phone)) {
                HexDetailRow(
                    AnnotatedString(model.name),
                    leading = { RowIcon(HexIcons.cpu) },
                    detail =
                        model.variant?.let { stringResource(R.string.speech_model_detail, it, SpeechDownload.megabytes(model.bytes)) }
                            ?: stringResource(R.string.speech_line_on_phone, SpeechDownload.megabytes(model.bytes)),
                )
                HexDetailRow(
                    AnnotatedString(model.runtime),
                    leading = { RowIcon(HexIcons.term) },
                    detail = model.runtimeDetail,
                    divider = true,
                )
                HexDetailRow(
                    AnnotatedString(stringResource(R.string.speech_runs_here)),
                    leading = { RowIcon(HexIcons.lock) },
                    detail = stringResource(R.string.speech_runs_here_sub),
                    divider = true,
                )
            }
            HexDetailsSection(stringResource(R.string.speech_pauses)) {
                HexDetailRow(
                    AnnotatedString(
                        stringResource(R.string.speech_pauses_title, model.pausesName, SpeechDownload.megabytes(model.pausesBytes)),
                    ),
                    leading = { RowIcon(HexIcons.pause) },
                    detail = stringResource(R.string.speech_pauses_sub),
                    action = { PausesState(model.pauses) },
                )
            }
        }
    }
}

/** The dial at 60 pt, a title in the state's colour, a line under it and a quiet third line (`.hero`). */
@Composable
private fun Hero(
    speech: SpeechStatus,
    model: SpeechModelInfo?,
) {
    val colors = HexTheme.colors
    val hero = SpeechHero.of(speech)
    val dial = SpeechDialLook.of(speech, model?.stopped)
    val download = (speech as? SpeechStatus.Loading)?.download
    val title =
        when (hero) {
            SpeechHero.NotOnPhone -> R.string.speech_hero_not_on_phone
            SpeechHero.Downloading -> R.string.speech_hero_downloading
            SpeechHero.ConnectionDropped -> R.string.speech_hero_dropped
            SpeechHero.Loading -> R.string.speech_hero_loading
            SpeechHero.WarmingUp -> R.string.speech_hero_warming
            SpeechHero.Ready -> R.string.speech_hero_ready
            SpeechHero.Failed -> R.string.speech_hero_failed
        }
    val titleColor =
        when (hero) {
            SpeechHero.ConnectionDropped -> colors.amber
            SpeechHero.Ready -> colors.green
            SpeechHero.Failed -> colors.red
            else -> colors.text1
        }
    val bytes = model?.bytes ?: 0
    val line =
        when (hero) {
            SpeechHero.NotOnPhone -> stringResource(R.string.speech_line_to_download, SpeechDownload.megabytes(model?.toDownload ?: 0))
            SpeechHero.Downloading, SpeechHero.ConnectionDropped ->
                stringResource(
                    R.string.speech_line_of,
                    SpeechDownload.megabytes(download?.done ?: 0),
                    SpeechDownload.megabytes(
                        download?.total ?: 0,
                    ),
                )
            SpeechHero.Loading -> stringResource(R.string.speech_line_loading)
            SpeechHero.WarmingUp -> stringResource(R.string.speech_line_warming)
            SpeechHero.Ready -> stringResource(R.string.speech_line_on_phone, SpeechDownload.megabytes(bytes))
            SpeechHero.Failed ->
                model?.stopped?.let {
                    stringResource(
                        R.string.speech_line_stopped,
                        SpeechDownload.megabytes(it.done),
                        SpeechDownload.megabytes(it.total),
                    )
                }
                    ?: stringResource(R.string.speech_line_on_phone, SpeechDownload.megabytes(bytes))
        }
    val note =
        when (hero) {
            SpeechHero.NotOnPhone -> stringResource(R.string.speech_note_starts)
            SpeechHero.Downloading -> speed(download)
            SpeechHero.ConnectionDropped -> stringResource(R.string.speech_note_resumes)
            SpeechHero.Loading -> stringResource(R.string.speech_note_loading)
            SpeechHero.WarmingUp -> stringResource(R.string.speech_note_warming)
            SpeechHero.Ready -> stringResource(R.string.speech_note_ready)
            SpeechHero.Failed -> stringResource(R.string.speech_note_failed)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 2.dp,
                    end = 2.dp,
                    top = 4.dp,
                    bottom = 16.dp,
                ).semantics(mergeDescendants = true) {},
    ) {
        HexSpeechDial(dial.mode, dial.progress, HexDialSize.Sheet)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = HexTheme.type.headline, color = titleColor)
            Text(line, style = HexTheme.type.body, color = colors.text1)
            Text(note, style = HexTheme.type.mono, color = colors.text3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** "8.4 MB/s · about 2 min left", or "measuring speed…" until the speed is known. */
@Composable
private fun speed(download: SpeechDownload?): String {
    val rate = download?.bytesPerSecond
    val left = download?.secondsLeft
    if (rate == null || left == null) return stringResource(R.string.speech_note_measuring)
    val perSecond = megabytesPerSecond(rate)
    return when (val minutes = SpeechDownload.minutesLeft(left)) {
        0L -> stringResource(R.string.speech_note_speed_soon, perSecond)
        else -> stringResource(R.string.speech_note_speed_left, perSecond, minutes)
    }
}

/** The speed's "8.4" in megabytes a second, with the decimal separator of [locale] ("8,4" in Germany). */
internal fun megabytesPerSecond(
    bytesPerSecond: Long,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, "%.1f", bytesPerSecond.toDouble() / SpeechDownload.MB)

/** Download → Load → Warm up → Ready (`.phases`): done ones with a check, the current one highlighted. */
@Composable
private fun Phases(phases: List<SpeechPhase>) {
    val colors = HexTheme.colors
    val names =
        listOf(
            stringResource(R.string.speech_phase_download),
            stringResource(R.string.speech_phase_load),
            stringResource(R.string.speech_phase_warm),
            stringResource(R.string.speech_phase_ready),
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 16.dp),
    ) {
        phases.forEachIndexed { i, phase ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    when (phase) {
                        SpeechPhase.Done -> Icon(HexIcons.check, null, tint = colors.green, modifier = Modifier.size(13.dp))
                        SpeechPhase.Ahead -> Mark(6.dp, colors.text4)
                        SpeechPhase.Now -> Mark(8.dp, colors.text1, ring = colors.tint(12f))
                        SpeechPhase.NowDropped -> Mark(8.dp, colors.amber)
                        SpeechPhase.Failed -> Mark(8.dp, colors.red)
                    }
                }
                val strong = phase == SpeechPhase.Now || phase == SpeechPhase.NowDropped || phase == SpeechPhase.Failed
                Text(
                    names[i],
                    style = HexTheme.type.caption.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal),
                    color =
                        when (phase) {
                            SpeechPhase.Ahead -> colors.text3
                            SpeechPhase.Done -> colors.text2
                            SpeechPhase.Failed -> colors.red
                            else -> colors.text1
                        },
                    maxLines = 1,
                )
            }
            if (i < phases.lastIndex) {
                // The line after a done phase is green.
                val done = phase == SpeechPhase.Done
                Box(
                    Modifier
                        .weight(1f)
                        .widthIn(min = 6.dp)
                        .padding(horizontal = 6.dp)
                        .height(1.dp)
                        .background(if (done) colors.green.copy(alpha = 0.45f) else colors.line2),
                )
            }
        }
    }
}

@Composable
private fun Mark(
    size: Dp,
    color: Color,
    ring: Color? = null,
) {
    Box(
        Modifier
            .then(if (ring != null) Modifier.size(size + 6.dp).background(ring, CircleShape).padding(3.dp) else Modifier)
            .size(size)
            .background(color, CircleShape),
    )
}

/**
 * The status lines (`.sline`), when they apply: the connection dropped, with Try now; it failed, with Try again; and
 * takes waiting for the model.
 */
@Composable
private fun StatusLines(
    speech: SpeechStatus,
    model: SpeechModelInfo?,
    draft: Draft,
    onTryNow: () -> Unit,
    onTryAgain: () -> Unit,
) {
    val colors = HexTheme.colors
    val download = (speech as? SpeechStatus.Loading)?.download
    val retryIn = download?.retryIn.takeIf { speech is SpeechStatus.Loading && speech.stage == SpeechStage.Download }
    if (retryIn != null) {
        StatusLine(
            HexIcons.wifi,
            colors.amber,
            colors.amberSoft,
            colors.amberLine,
            if (retryIn > 0) stringResource(R.string.speech_retry_in, retryIn) else stringResource(R.string.speech_retry_trying),
            stringResource(R.string.speech_retry_rule),
        ) { HexButton(onClick = onTryNow, text = stringResource(R.string.speech_try_now)) }
    }
    if (speech == SpeechStatus.Failed) {
        val stopped = model?.stopped
        StatusLine(
            HexIcons.alert,
            colors.red,
            colors.redSoft,
            colors.red.copy(alpha = 0.30f),
            stringResource(if (stopped != null) R.string.speech_failed_download else R.string.speech_failed_load),
            if (stopped != null) {
                stringResource(R.string.speech_failed_download_sub, SpeechDownload.megabytes(stopped.done))
            } else {
                stringResource(R.string.speech_failed_load_sub)
            },
            strong = false,
        ) { HexButton(onClick = onTryAgain, text = stringResource(R.string.speech_try_again), style = HexButtonStyle.Primary) }
    }
    val waiting =
        draft.chunks.filter {
            it.state == ChunkState.Waiting || (speech == SpeechStatus.Failed && (it.state as? ChunkState.Failed)?.retry == true)
        }
    if (speech != SpeechStatus.Ready && waiting.isNotEmpty()) {
        StatusLine(
            HexIcons.mic,
            colors.text3,
            colors.panel2,
            colors.line,
            pluralStringResource(R.plurals.speech_waiting, waiting.size, waiting.size, clock(waiting.sumOf { it.durationMs })),
            stringResource(R.string.speech_waiting_sub),
        )
    }
}

@Composable
private fun StatusLine(
    icon: ImageVector,
    tint: Color,
    fill: Color,
    border: Color,
    title: String,
    detail: String,
    strong: Boolean = true,
    action: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .background(fill, shape)
                .border(1.dp, border, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.align(Alignment.Top).padding(top = 1.dp).size(15.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = HexTheme.type.callout.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal),
                color = HexTheme.colors.text1,
            )
            Text(detail, style = HexTheme.type.footnote, color = HexTheme.colors.text2)
        }
        action?.invoke()
    }
}

@Composable
private fun RowIcon(icon: ImageVector) = Icon(icon, null, tint = HexTheme.colors.text3, modifier = Modifier.size(15.dp))

/** The pause detector's own state on the right of its row: a dot and the word. */
@Composable
private fun PausesState(state: VoiceEngine.PausesState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HexDot(
            when (state) {
                VoiceEngine.PausesState.Downloaded, VoiceEngine.PausesState.Ready -> HexDotTone.Green
                else -> HexDotTone.Neutral
            },
        )
        Text(
            stringResource(
                when (state) {
                    VoiceEngine.PausesState.NotOnPhone -> R.string.speech_pauses_not_on_phone
                    VoiceEngine.PausesState.Downloaded -> R.string.speech_pauses_downloaded
                    VoiceEngine.PausesState.Loading -> R.string.speech_pauses_loading
                    VoiceEngine.PausesState.Ready -> R.string.speech_pauses_ready
                },
            ),
            style = HexTheme.type.mono,
            color = HexTheme.colors.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
