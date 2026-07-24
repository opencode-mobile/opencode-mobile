package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val ChipShape = RoundedCornerShape(999.dp)

@Composable
fun OpenCodeFilterChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val textStrong = openCodeColor("text-strong")
    val textBase = openCodeColor("text-base")
    val textWeaker = openCodeColor("text-weaker")
    val borderWeak = openCodeColor("border-weak-base")
    val labelColor = if (selected) textStrong else textWeaker
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
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) borderWeak else borderWeak.copy(alpha = 0.55f),
                    shape = ChipShape,
                )
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = labelColor,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                color = if (selected) textStrong else textBase,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier =
                    Modifier
                        .clip(ChipShape)
                        .background(textStrong.copy(alpha = if (selected) 0.16f else 0.06f))
                        .padding(
                            horizontal = if (count >= 10) 8.dp else 6.dp,
                            vertical = 2.dp,
                        )
                        .then(
                            when {
                                count >= 100 -> Modifier.widthIn(min = 34.dp)
                                count >= 10 -> Modifier.widthIn(min = 28.dp)
                                else -> Modifier.widthIn(min = 22.dp)
                            },
                        ),
                textAlign = TextAlign.Center,
            )
        }
    }
}
