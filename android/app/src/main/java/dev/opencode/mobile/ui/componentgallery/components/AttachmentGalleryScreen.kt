package dev.opencode.mobile.ui.componentgallery.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeAttachmentChip
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentUploadState
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeMessageAttachment
import java.io.ByteArrayOutputStream

@Composable
fun AttachmentGalleryScreen() {
    var chips by remember { mutableStateOf(AttachmentExamples) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pending", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { attachment ->
                OpenCodeAttachmentChip(
                    attachment = attachment,
                    onRemove = { id -> chips = chips.filterNot { it.id == id } },
                )
            }
        }

        Text("Uploading", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UploadingExamples.forEach { attachment ->
                OpenCodeAttachmentChip(attachment = attachment, onRemove = {})
            }
        }

        Text("Sent", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttachmentExamples.forEach { attachment ->
                OpenCodeMessageAttachment(attachment)
            }
        }

        Text("Sent images (aspect kept, height capped, tap to view)", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageRatioExamples.forEach { attachment ->
                OpenCodeMessageAttachment(attachment)
            }
        }
    }
}

private val UploadingExamples =
    listOf(
        OpenCodeAttachmentPresentation(
            id = "uploading",
            filename = "build-output.zip",
            variant = OpenCodeAttachmentVariant.File,
            uploadState = OpenCodeAttachmentUploadState.InProgress(42),
        ),
        OpenCodeAttachmentPresentation(
            id = "starting",
            filename = "recording.mov",
            variant = OpenCodeAttachmentVariant.File,
            uploadState = OpenCodeAttachmentUploadState.InProgress(null),
        ),
    )

private fun ratioImage(
    id: String,
    label: String,
    width: Int,
    height: Int,
    color: Int,
): OpenCodeAttachmentPresentation {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(color)
        val paint =
            Paint().apply {
                this.color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
        drawRect(3f, 3f, width - 3f, height - 3f, paint)
        drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    return OpenCodeAttachmentPresentation(
        id = id,
        filename = label,
        variant = OpenCodeAttachmentVariant.Image,
        imageBytes = bytes,
    )
}

private val ImageRatioExamples =
    listOf(
        ratioImage("r169", "16:9.png", 640, 360, 0xFF3B6EA8.toInt()),
        ratioImage("r34", "3:4.png", 300, 400, 0xFF7A4E9A.toInt()),
        ratioImage("r11", "square.png", 400, 400, 0xFF4E8A5B.toInt()),
        ratioImage("pano", "panorama.png", 1200, 300, 0xFFA8693B.toInt()),
        ratioImage("phone", "phone-screenshot.png", 360, 780, 0xFF8A4E4E.toInt()),
    )

private val AttachmentExamples =
    listOf(
        OpenCodeAttachmentPresentation(
            id = "image",
            filename = "workspace-screenshot.png",
            variant = OpenCodeAttachmentVariant.Image,
            imageBytes =
                Base64.decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                    Base64.DEFAULT,
                ),
        ),
        OpenCodeAttachmentPresentation("pdf", "architecture-notes.pdf", OpenCodeAttachmentVariant.Pdf),
        OpenCodeAttachmentPresentation("text", "debug-output.txt", OpenCodeAttachmentVariant.Text),
        OpenCodeAttachmentPresentation("file", "build-output.zip", OpenCodeAttachmentVariant.File),
        OpenCodeAttachmentPresentation(
            "long",
            "a-very-long-filename-that-demonstrates-single-line-truncation-in-the-chip.md",
            OpenCodeAttachmentVariant.Text,
        ),
    )
