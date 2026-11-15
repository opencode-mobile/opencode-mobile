package dev.opencode.hexcode.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexDialSize
import dev.opencode.hexcode.designsystem.components.HexSpeechDial

/**
 * The speech line (app-plan.md, Speech line and dial › Speech line): a quiet row above the dock in the waiting line's
 * slot and style, with the dial at 16 pt, the words, and a chevron. "Speech ready" ([ready]) has the green dial and no
 * chevron. It opens the speech model sheet.
 */
@Composable
internal fun SpeechLine(
    speech: SpeechStatus,
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.m)
    val shown = if (ready) SpeechStatus.Ready else speech
    val dial = SpeechDialLook.of(shown)
    val open = stringResource(R.string.home_speech_a11y_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .border(1.dp, colors.line, shape)
                .clickable(onClickLabel = open, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        HexSpeechDial(dial.mode, dial.progress, HexDialSize.Line)
        Text(
            speechLineWords(shown),
            style = HexTheme.type.footnote,
            color = colors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!ready) Icon(HexIcons.chevR, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall))
    }
}

/**
 * The speech line's words: "Speech model · 120 of 640 MB", "Speech model · retrying in 4 s" (then "trying again…"),
 * "Speech model loading…" while it loads and warms up, "Speech model didn't load", "Speech model not on this phone yet"
 * and "Speech ready".
 */
@Composable
internal fun speechLineWords(speech: SpeechStatus): String =
    when (speech) {
        SpeechStatus.Ready -> stringResource(R.string.home_speech_ready)
        SpeechStatus.Failed -> stringResource(R.string.home_speech_failed)
        is SpeechStatus.Loading -> {
            val download = speech.download
            when (speech.stage) {
                SpeechStage.NotOnPhone -> stringResource(R.string.home_speech_not_on_phone)
                SpeechStage.Download ->
                    when {
                        download == null -> stringResource(R.string.home_speech_loading)
                        download.retryIn != null -> retrying(download.retryIn)
                        else ->
                            stringResource(
                                R.string.home_speech_downloading,
                                download.done / SpeechDownload.MB,
                                download.total / SpeechDownload.MB,
                            )
                    }
                SpeechStage.Load, SpeechStage.WarmUp -> stringResource(R.string.home_speech_loading)
            }
        }
    }

/** "Speech model · retrying in 4 s", or "Speech model · trying again…" at 0. */
@Composable
internal fun retrying(seconds: Int): String =
    if (seconds > 0) stringResource(R.string.home_speech_retrying, seconds) else stringResource(R.string.home_speech_trying)
