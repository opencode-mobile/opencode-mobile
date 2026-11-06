package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/** One choice in a [HexSegmented]. */
data class HexSegment(
    val id: String,
    val label: String,
    /** Selected, its label is amber: a choice with consequences, such as Now. */
    val warning: Boolean = false,
)

/** 36 dp and full width (a setting), or 30 dp, `caption` medium and as wide as its labels (`.mode`, beside Send). */
enum class HexSegmentedSize { Regular, Small }

/**
 * A segmented choice (`.mp-seg`): segments in a 36 pt `tint(6)` track, the selected one raised on `panel3`. Without
 * [segments] it shows [emptyText] in the track instead (a model with no reasoning levels).
 */
@Composable
fun HexSegmented(
    segments: List<HexSegment>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    size: HexSegmentedSize = HexSegmentedSize.Regular,
) {
    val colors = HexTheme.colors
    val small = size == HexSegmentedSize.Small
    val track = RoundedCornerShape(HexTheme.radius.m)
    val segment = RoundedCornerShape(HexTheme.radius.s)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .then(if (small) Modifier.height(30.dp) else Modifier.fillMaxWidth().height(36.dp))
                .clip(track)
                .then(
                    if (small) {
                        Modifier
                            .background(
                                colors.background,
                            ).border(1.dp, colors.line, track)
                    } else {
                        Modifier.background(colors.tint(6f))
                    },
                ).padding(if (small) 2.dp else 3.dp)
                .selectableGroup(),
    ) {
        if (segments.isEmpty()) {
            Text(
                emptyText.orEmpty(),
                style = HexTheme.type.caption,
                color = colors.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 9.dp),
            )
        }
        segments.forEach { item ->
            val selected = item.id == selectedId
            val fill by animateColorAsState(if (selected) colors.panel3 else Color.Transparent, HexTheme.motion.fade(), label = "segment")
            val text by animateColorAsState(
                when {
                    selected && item.warning -> colors.amber
                    selected -> colors.text1
                    small -> colors.text3
                    else -> colors.text2
                },
                HexTheme.motion.fade(),
                label = "segment-text",
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .then(if (small) Modifier.padding(0.dp) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .then(
                            if (selected) {
                                Modifier.shadow(
                                    1.dp,
                                    segment,
                                    ambientColor = colors.shadow,
                                    spotColor = colors.shadow,
                                )
                            } else {
                                Modifier
                            },
                        ).clip(segment)
                        .background(fill)
                        .then(if (selected) Modifier.border(1.dp, colors.line, segment) else Modifier)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.id) })
                        .then(if (small) Modifier.padding(horizontal = 10.dp) else Modifier),
            ) {
                Text(
                    item.label,
                    style = if (small) HexTheme.type.caption.copy(fontWeight = FontWeight.Medium) else HexTheme.type.callout,
                    color = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
