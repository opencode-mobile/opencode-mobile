package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ChipShape = RoundedCornerShape(999.dp)
private val ChipMetaSize = 20.dp
private val ChipRemoveIconInset = 4.dp

@Composable
fun OpenCodeFilterChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onRemove: (() -> Unit)? = null,
) {
    val textStrong = openCodeColor("text-strong")
    val borderWeak = openCodeColor("border-weak-base")
    // Selected vs unselected differs by chip background only — label and count
    // badge colors stay constant.
    val fill =
        if (selected) {
            textStrong.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        }

    Row(
        modifier =
            modifier
                .semantics { this.selected = selected }
                .clip(ChipShape)
                .background(fill)
                .border(
                    width = 1.dp,
                    color = borderWeak.copy(alpha = 0.55f),
                    shape = ChipShape,
                )
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 36.dp)
                .padding(
                    start = 12.dp,
                    top = 8.dp,
                    end = if (onRemove != null) 6.dp else 12.dp,
                    bottom = 8.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = textStrong,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            maxLines = 1,
        )
        if (count != null) {
            Box(
                modifier =
                    Modifier
                        .height(ChipMetaSize)
                        .widthIn(
                            min =
                                when {
                                    count >= 100 -> 34.dp
                                    count >= 10 -> 28.dp
                                    else -> ChipMetaSize
                                },
                        )
                        .clip(ChipShape)
                        .background(textStrong.copy(alpha = 0.10f))
                        .padding(horizontal = if (count >= 10) 8.dp else 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = count.toString(),
                    color = textStrong,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (onRemove != null) {
            Icon(
                imageVector = OpenCodeIcons.Close,
                contentDescription = "Remove $title",
                tint = textStrong.copy(alpha = 0.85f),
                modifier =
                    Modifier
                        .size(ChipMetaSize)
                        .clip(CircleShape)
                        .background(textStrong.copy(alpha = 0.10f))
                        .clickable(role = Role.Button, onClick = onRemove)
                        .padding(ChipRemoveIconInset),
            )
        }
    }
}
