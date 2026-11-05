package dev.opencode.hexcode.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import kotlinx.coroutines.delay

/** A one-line notice, with an optional action such as Undo. A new [id] shows it again. */
data class NoticeMessage(
    val text: String,
    val actionLabel: String? = null,
    val id: Long = System.nanoTime(),
)

/**
 * Shows [message] as a card at the top of the screen (the prototype's toast) for 4 s, or 10 s with an action, then
 * calls [onDismissed]. Home and Settings use it until the design system's HexToast (M2).
 */
@Composable
fun NoticeHost(
    message: NoticeMessage?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    val dismissed by rememberUpdatedState(onDismissed)
    var shown by remember { mutableStateOf<NoticeMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        shown = current
        visible = true
        delay(if (current.actionLabel != null) 10_000 else 4_000)
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
        val notice = shown ?: return@AnimatedVisibility
        val colors = HexTheme.colors
        val shape = RoundedCornerShape(HexTheme.radius.l)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .shadow(16.dp, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
                    .background(colors.panel2, shape)
                    .border(1.dp, colors.line2, shape)
                    .heightIn(min = 44.dp)
                    .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(notice.text, style = HexTheme.type.callout, color = colors.text1, modifier = Modifier.weight(1f))
            notice.actionLabel?.let { label ->
                HexButton(
                    onClick = {
                        onAction()
                        visible = false
                        dismissed()
                    },
                    text = label,
                )
            }
        }
    }
}
