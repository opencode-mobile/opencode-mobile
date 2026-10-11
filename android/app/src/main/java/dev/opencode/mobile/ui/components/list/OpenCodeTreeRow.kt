package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Indent added per tree depth level. */
val OpenCodeTreeRowIndent: Dp = 16.dp

private val TreeRowBaseInset = 8.dp
private val TreeRowMinHeight = 36.dp
private val TreeRowMinHeightWithSubtitle = 48.dp
private val TreeRowDisclosureSlot = 20.dp
private val TreeRowIconSize = 18.dp
private val TreeRowSpacing = 6.dp
private const val TREE_ROW_DIMMED_ALPHA = 0.5f

enum class OpenCodeTreeRowDisclosure {
    /** Leaf row: the disclosure slot stays empty so titles align. */
    None,
    Collapsed,
    Expanded,
}

enum class OpenCodeTreeRowState {
    Normal,
    Selected,

    /** De-emphasised, for example a `.gitignore`d entry; pair with a tag. */
    Dimmed,

    /** Children are loading: shows a trailing spinner. */
    Loading,
}

/**
 * One row of a hierarchical list (File Browser tree, find results). Depth indents by
 * [OpenCodeTreeRowIndent]; folders show a rotating disclosure chevron.
 */
@Composable
fun OpenCodeTreeRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    subtitle: String? = null,
    disclosure: OpenCodeTreeRowDisclosure = OpenCodeTreeRowDisclosure.None,
    state: OpenCodeTreeRowState = OpenCodeTreeRowState.Normal,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    require(title.isNotBlank()) { "Tree Row requires a non-blank title" }
    val selected = state == OpenCodeTreeRowState.Selected
    val contentAlpha = if (state == OpenCodeTreeRowState.Dimmed) TREE_ROW_DIMMED_ALPHA else 1f
    val shape = RoundedCornerShape(OpenCodeRadius.small)
    val chevronRotation by animateFloatAsState(
        targetValue = if (disclosure == OpenCodeTreeRowDisclosure.Expanded) 90f else 0f,
        label = "treeRowDisclosure",
    )
    val resolvedSubtitle = subtitle?.takeIf { it.isNotBlank() }
    val description =
        listOfNotNull(
            title,
            if (disclosure == OpenCodeTreeRowDisclosure.None) null else "folder",
            resolvedSubtitle,
            tag,
        ).joinToString(", ")
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = TreeRowBaseInset)
                .clip(shape)
                .background(if (selected) openCodeColor("surface-raised-base") else Color.Transparent, shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).heightIn(min = if (resolvedSubtitle != null) TreeRowMinHeightWithSubtitle else TreeRowMinHeight)
                .padding(start = OpenCodeTreeRowIndent * depth.coerceAtLeast(0), end = TreeRowBaseInset)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    this.selected = selected
                    when (disclosure) {
                        OpenCodeTreeRowDisclosure.Expanded -> stateDescription = "Expanded"
                        OpenCodeTreeRowDisclosure.Collapsed -> stateDescription = "Collapsed"
                        OpenCodeTreeRowDisclosure.None -> Unit
                    }
                },
        horizontalArrangement = Arrangement.spacedBy(TreeRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(TreeRowDisclosureSlot), contentAlignment = Alignment.Center) {
            if (disclosure != OpenCodeTreeRowDisclosure.None) {
                Icon(
                    imageVector = OpenCodeIcons.ChevronRight,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(16.dp)
                            .rotate(chevronRotation),
                    tint = openCodeColor("icon-base"),
                )
            }
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier =
                Modifier
                    .size(TreeRowIconSize)
                    .alpha(contentAlpha),
            tint = if (selected) openCodeColor("icon-strong-base") else openCodeColor("icon-base"),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .alpha(contentAlpha)
                    .padding(vertical = 6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (resolvedSubtitle != null) {
                Text(
                    text = resolvedSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weaker"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tag != null) {
            OpenCodeTag(title = tag)
        }
        if (state == OpenCodeTreeRowState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = openCodeColor("text-base"),
                trackColor = openCodeColor("border-weaker-base"),
            )
        }
    }
}

@Preview(showBackground = true, name = "Tree Row")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Tree Row Dark")
@Composable
private fun OpenCodeTreeRowPreview() {
    OpenCodeTheme {
        Column {
            OpenCodeTreeRow("src", OpenCodeIcons.Folder, disclosure = OpenCodeTreeRowDisclosure.Expanded)
            OpenCodeTreeRow("main.kt", OpenCodeIcons.Description, depth = 1, state = OpenCodeTreeRowState.Selected)
            OpenCodeTreeRow(
                "build",
                OpenCodeIcons.Folder,
                disclosure = OpenCodeTreeRowDisclosure.Collapsed,
                state = OpenCodeTreeRowState.Dimmed,
                tag = "ignored",
            )
            OpenCodeTreeRow(
                "docs",
                OpenCodeIcons.Folder,
                disclosure = OpenCodeTreeRowDisclosure.Expanded,
                state = OpenCodeTreeRowState.Loading,
            )
            OpenCodeTreeRow("Button.kt", OpenCodeIcons.Description, subtitle = "ui/components")
        }
    }
}
