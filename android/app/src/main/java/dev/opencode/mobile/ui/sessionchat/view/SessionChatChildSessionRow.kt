package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed
import kotlinx.coroutines.delay

private val RowCornerRadius = 8.dp
private val LetterBadgeSize = 32.dp
private val LetterGlyphSize = 14.sp

@Composable
fun SessionChatChildSessionRow(
    agentName: String,
    letter: String,
    isBackground: Boolean,
    secondLine: String?,
    timeStartEpochMs: Long?,
    showsChevron: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val nowEpochMs by produceState(
        initialValue = System.currentTimeMillis(),
        timeStartEpochMs,
    ) {
        if (timeStartEpochMs == null) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = SessionChatChildSessionElapsed.format(timeStartEpochMs, nowEpochMs)
    val accessibility =
        buildList {
            add(agentName)
            if (isBackground) add("background")
            if (!secondLine.isNullOrEmpty()) add(secondLine)
            if (elapsed != null) add(elapsed)
        }.joinToString(", ")
    val shape = RoundedCornerShape(RowCornerRadius)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibility
                    if (onClick != null) {
                        role = Role.Button
                    }
                }
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(LetterBadgeSize)
                    .background(openCodeColor("surface-raised-base"), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = letter,
                color = openCodeColor("text-strong"),
                fontSize = LetterGlyphSize,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBackground) {
                    Text(
                        text = "background",
                        style = MaterialTheme.typography.bodySmall,
                        color = openCodeColor("text-weaker"),
                        maxLines = 1,
                    )
                }
            }
            if (!secondLine.isNullOrEmpty()) {
                Text(
                    text = secondLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (elapsed != null) {
            Text(
                text = elapsed,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weaker"),
                maxLines = 1,
            )
        }
        if (showsChevron) {
            Icon(
                imageVector = OpenCodeIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = openCodeColor("icon-base"),
            )
        }
    }
}
