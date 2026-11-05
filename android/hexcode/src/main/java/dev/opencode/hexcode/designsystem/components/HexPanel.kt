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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * A bottom sheet (`.sheet`) over [scrim]-dimmed content: `panel`, radius 20 at the top, the grab bar, and a head with
 * the [title] and one action each side. Put it last in a full-screen `Box`; back and a tap on the scrim call
 * [onDismiss].
 */
@Composable
fun BoxScope.HexSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    leading: HexSheetAction? = null,
    trailing: HexSheetAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HexTheme.colors
    val motion = HexTheme.motion
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
    AnimatedVisibility(
        visible,
        enter = slideInVertically(motion.sheet()) { it },
        exit = slideOutVertically(motion.sheet()) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val shape = RoundedCornerShape(topStart = HexTheme.radius.sheet, topEnd = HexTheme.radius.sheet)
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.panel)
                    .border(1.dp, colors.line2, shape)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.line2, CircleShape),
            )
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
            Column(Modifier.padding(top = 12.dp), content = content)
        }
    }
}
