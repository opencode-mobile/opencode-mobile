package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeMediumShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Raised card with a title, blurb, selected check, and a preview slot. */
@Composable
fun OpenCodeSelectableCard(
    title: String,
    blurb: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    require(title.isNotBlank()) { "Selectable Card requires a non-blank title" }
    val shape = OpenCodeMediumShape
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(openCodeColor("surface-raised-base"), shape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, openCodeColor("interactive"), shape)
                    } else {
                        Modifier
                    },
                )
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = openCodeColor("text-strong"))
                Text(blurb, style = MaterialTheme.typography.bodySmall, color = openCodeColor("text-base"))
            }
            if (selected) {
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.size(18.dp),
                    tint = openCodeColor("interactive"),
                )
            }
        }
        preview()
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSelectableCardPreview() {
    OpenCodeTheme {
        OpenCodeSelectableCard(
            title = "Porcelain",
            blurb = "Warm paper and ink.",
            selected = true,
            onClick = {},
        ) {
            Text("Preview", style = MaterialTheme.typography.bodySmall)
        }
    }
}
