package dev.opencode.hexcode.designsystem.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme

/** A card (`.panel`): `panel` fill, `line` border, radius 12. [translucent] lets a camera view show through a little. */
@Composable
fun HexPanel(
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(if (translucent) colors.panel.copy(alpha = 0.92f) else colors.panel)
                .border(1.dp, colors.line, shape),
        content = content,
    )
}

/** A button at one end of a [HexSheet]'s head. */
data class HexSheetAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A [HexSheet]'s **request** head (`.rq-head`), for an approval or a question: the title on the left, an optional
 * [count] ("1 of 2"), and a round Hide button ([hideLabel], `chevD`) that calls [onHide].
 */
data class HexSheetRequest(
    val hideLabel: String,
    val onHide: () -> Unit,
    val count: String? = null,
)

/**
 * A bottom sheet (`.sheet`) over [scrim]-dimmed content: `panel`, radius 20 at the top, the grab bar, and a head with
 * the [title] and one action each side, or with a [request] the request head. [amberEdge] marks an approval (`.perm-sh`):
 * a 1 pt `amber` 60 % top edge with an `amber` 25 % line above it. The content scrolls when it's taller than the
 * screen; a [footer] (the sheet's buttons) stays under it. Put it last in a full-screen `Box`; back and a tap on the
 * scrim call [onDismiss].
 */
@Composable
fun BoxScope.HexSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    leading: HexSheetAction? = null,
    trailing: HexSheetAction? = null,
    request: HexSheetRequest? = null,
    amberEdge: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    /** A head of the sheet's own in place of the centred title ([HexReader]'s). */
    head: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HexTheme.colors
    val motion = HexTheme.motion
    val reduced = HexMotion.reduced
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .semantics { hideFromAccessibility() }
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        )
    }
    // With Reduce Motion it fades instead of sliding up (app-plan.md, Motion and Reduce Motion).
    AnimatedVisibility(
        visible,
        enter = if (reduced) fadeIn(motion.fade()) else slideInVertically(motion.sheet()) { it },
        exit = if (reduced) fadeOut(motion.fade()) else slideOutVertically(motion.sheet()) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val radius = HexTheme.radius.sheet
        val shape = RoundedCornerShape(topStart = radius, topEnd = radius)
        val amber = colors.amber
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(top = if (amberEdge) 2.dp else 0.dp)
                    .drawWithContent {
                        drawContent()
                        if (amberEdge) amberTop(amber, radius.toPx())
                    }.clip(shape)
                    .background(colors.panel)
                    .border(1.dp, colors.line2, shape)
                    // Swallows taps on the sheet so they don't reach the scrim, without a click for TalkBack: the
                    // sheet's own buttons stay separate items.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // TalkBack hears the sheet open by its title.
                    .semantics { paneTitle = title }
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = if (request != null) 2.dp else 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.line2, CircleShape),
            )
            if (head != null) {
                head()
            } else if (request != null) {
                RequestHead(title, request)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                ) {
                    Box(Modifier.widthIn(min = 70.dp)) {
                        leading?.let { HexButton(onClick = it.onClick, text = it.label) }
                    }
                    Text(
                        title,
                        style = HexTheme.type.bodyStrong,
                        color = colors.text1,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Box(Modifier.widthIn(min = 70.dp), contentAlignment = Alignment.CenterEnd) {
                        trailing?.let { HexButton(onClick = it.onClick, text = it.label) }
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = if (request != null) 4.dp else 12.dp),
                content = content,
            )
            if (footer != null) Column(Modifier.padding(top = 16.dp), content = footer)
        }
    }
}

/** The request head (`.rq-head`): the title (`headline`), the count (`mono`, `text3`), and the 36 pt round Hide. */
@Composable
private fun RequestHead(
    title: String,
    request: HexSheetRequest,
) {
    val colors = HexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = HexTheme.size.touch),
    ) {
        Text(
            title,
            style = HexTheme.type.headline,
            color = colors.text1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        request.count?.let { Text(it, style = HexTheme.type.mono, color = colors.text3, maxLines = 1) }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.tint(5f))
                    .clickable(role = Role.Button, onClick = request.onHide)
                    .semantics { contentDescription = request.hideLabel },
        ) {
            Icon(HexIcons.chevD, null, tint = colors.text2, modifier = Modifier.size(HexTheme.size.icon))
        }
    }
}

/** An approval sheet's top edge: `amber` 60 % along the rounded top, and an `amber` 25 % line 1 pt above it. */
private fun DrawScope.amberTop(
    amber: Color,
    radius: Float,
) {
    val stroke = 1.dp.toPx()

    fun edge(inset: Float): Path =
        Path().apply {
            val r = radius - inset
            moveTo(inset, radius)
            arcTo(Rect(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f, false)
            lineTo(size.width - radius, inset)
            arcTo(Rect(size.width - inset - 2 * r, inset, size.width - inset, inset + 2 * r), 270f, 90f, false)
        }
    drawPath(edge(-stroke / 2f), amber.copy(alpha = 0.25f), style = Stroke(stroke))
    drawPath(edge(stroke / 2f), amber.copy(alpha = 0.6f), style = Stroke(stroke))
}
