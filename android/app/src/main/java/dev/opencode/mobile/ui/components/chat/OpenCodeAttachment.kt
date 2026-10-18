package dev.opencode.mobile.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

enum class OpenCodeAttachmentVariant(
    val label: String,
) {
    Image("Image"),
    Pdf("PDF"),
    Text("Text"),
}

data class OpenCodeAttachmentPresentation(
    val id: String,
    val filename: String,
    val variant: OpenCodeAttachmentVariant,
    val imageBytes: ByteArray? = null,
)

/**
 * Removable attachment row. [onClick], when set, makes the row open the attachment
 * (for example to mark up a screenshot or preview a log); [onClickLabel] names that action.
 */
@Composable
fun OpenCodeAttachmentChip(
    attachment: OpenCodeAttachmentPresentation,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val kindLabel = attachment.variant.label.uppercase()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, openCodeColor("border-weaker-base"), AttachmentShape)
                .background(openCodeColor("surface-raised-base"), AttachmentShape)
                .then(
                    if (onClick != null) {
                        Modifier
                            .clip(AttachmentShape)
                            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AttachmentPreview(attachment, Modifier.size(36.dp))
        AttachmentLabels(
            attachment,
            Modifier
                .weight(1f)
                .semantics { contentDescription = "${attachment.filename}, $kindLabel" },
        )
        IconButton(onClick = { onRemove(attachment.id) }, modifier = Modifier.size(36.dp)) {
            Icon(
                OpenCodeIcons.Close,
                contentDescription = "Remove ${attachment.filename}",
                tint = openCodeColor("icon-strong-base"),
            )
        }
    }
}

@Composable
fun OpenCodeMessageAttachment(
    attachment: OpenCodeAttachmentPresentation,
    modifier: Modifier = Modifier,
) {
    val kindLabel = attachment.variant.label.uppercase()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, openCodeColor("border-weaker-base"), AttachmentShape)
                .background(openCodeColor("surface-raised-base"), AttachmentShape)
                .padding(8.dp)
                .semantics { contentDescription = "${attachment.filename}, $kindLabel" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AttachmentPreview(
            attachment,
            if (attachment.imageBytes != null) Modifier.size(width = 72.dp, height = 54.dp) else Modifier.size(40.dp),
        )
        AttachmentLabels(attachment, Modifier.weight(1f))
    }
}

@Composable
private fun AttachmentPreview(
    attachment: OpenCodeAttachmentPresentation,
    modifier: Modifier,
) {
    val bitmap =
        remember(attachment.id, attachment.imageBytes) {
            attachment.imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
    Box(
        modifier = modifier.clip(AttachmentShape).background(openCodeColor("surface-raised-base")),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                OpenCodeIcons.Description,
                contentDescription = null,
                tint = openCodeColor("icon-base"),
            )
        }
    }
}

@Composable
private fun AttachmentLabels(
    attachment: OpenCodeAttachmentPresentation,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = attachment.filename,
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-strong"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = attachment.variant.label.uppercase(),
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
        )
    }
}

private val AttachmentShape = OpenCodeSmallShape
