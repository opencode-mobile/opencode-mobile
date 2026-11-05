package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import kotlinx.coroutines.delay

/** A [HexToast]'s icon and its colour (the prototype's `.toast.t-*`). */
enum class HexToastTone {
    /** Red stop icon: stopped, hold to stop. */
    Stop,

    /** Amber info icon: something to know, such as a failed check or a setting not built yet. */
    Info,

    /** Green steer icon: done, sent, saved, copied. */
    Steer,

    /** The sun in `text2`: a new day or a new session. */
    Sun,

    /** The undo arrow in `text2`: something was taken away, such as a dropped take or a deleted chunk. */
    Undo,
}

/** One notice for [HexToastHost]. A new [id] shows it again, even with the same text. */
data class HexToastMessage(
    val title: String,
    val tone: HexToastTone,
    val detail: String? = null,
    /** A small button at the end, such as Undo. */
    val actionLabel: String? = null,
    val actionIcon: ImageVector? = null,
    val durationMs: Long = DEFAULT_MS,
    val id: Long = System.nanoTime(),
) {
    companion object {
        const val DEFAULT_MS = 2_400L
    }
}

/**
 * The prototype's `.toast`: a `panel2` card with a `line2` border and a shadow, an icon in its [tone], the [title]
 * and a one-line [detail], and an optional action button.
 */
@Composable
fun HexToast(
    title: String,
    tone: HexToastTone,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    val (icon, tint) =
        when (tone) {
            HexToastTone.Stop -> HexIcons.stop to colors.red
            HexToastTone.Info -> HexIcons.info to colors.amber
            HexToastTone.Steer -> HexIcons.steer to colors.green
            HexToastTone.Sun -> HexIcons.sun to colors.text2
            HexToastTone.Undo -> HexIcons.undo to colors.text2
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
                .background(colors.panel2, shape)
                .border(1.dp, colors.line2, shape)
                .heightIn(min = HexTheme.size.touch)
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(HexTheme.size.icon))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = HexTheme.type.callout, color = colors.text1)
            if (detail != null) {
                Text(detail, style = HexTheme.type.caption, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (actionLabel != null) HexButton(onClick = onAction, text = actionLabel, icon = actionIcon)
    }
}

/**
 * Shows [message] as a [HexToast] for its `durationMs`, then calls [onDismissed]; tapping its action calls [onAction]
 * and dismisses it. Put it at the top of the screen, over the content, 12 dp from each side.
 */
@Composable
fun HexToastHost(
    message: HexToastMessage?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    val dismissed by rememberUpdatedState(onDismissed)
    var shown by remember { mutableStateOf<HexToastMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        shown = current
        visible = true
        delay(current.durationMs)
        visible = false
        dismissed()
    }
    val motion = HexTheme.motion
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fade()) + slideInVertically(motion.fade()) { -it / 4 },
        exit = fadeOut(motion.fade()),
        modifier = modifier,
    ) {
        val toast = shown ?: return@AnimatedVisibility
        HexToast(
            title = toast.title,
            tone = toast.tone,
            detail = toast.detail,
            actionLabel = toast.actionLabel,
            actionIcon = toast.actionIcon,
            onAction = {
                onAction()
                visible = false
                dismissed()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
