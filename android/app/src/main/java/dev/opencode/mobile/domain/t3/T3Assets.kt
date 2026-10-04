package dev.opencode.mobile.domain.t3

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object T3Assets {
    const val CREATE_URL = "assets.createUrl"
    const val CREATE_UPLOAD_URL = "attachments.createUploadUrl"
    const val DELETE_UPLOAD = "attachments.delete"
    const val PREVIEW_MIME = "image/jpeg"
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    /** Image types a T3 provider turn accepts (`PROVIDER_SEND_TURN_SUPPORTED_IMAGE_MIME_TYPES`). */
    val SUPPORTED_IMAGE_MIMES: Set<String> = setOf("image/gif", "image/jpeg", "image/png", "image/webp")

    fun createUrlPayload(
        attachmentId: String,
        fileName: String,
        mimeType: String,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("resource") {
                put("_tag", "attachment")
                put("attachmentId", attachmentId)
                put("fileName", fileName)
                put("mimeType", mimeType)
                put("disposition", "inline")
            }
        }

    /** `AttachmentCreateUploadUrlInput` for an image upload. */
    fun createUploadUrlPayload(
        name: String,
        mimeType: String,
        sizeBytes: Long,
    ): JsonObject =
        buildJsonObject {
            put("type", "image")
            put("name", name)
            put("mimeType", mimeType)
            put("sizeBytes", sizeBytes)
        }

    fun deleteUploadPayload(attachmentId: String): JsonObject = buildJsonObject { put("attachmentId", attachmentId) }

    fun relativeUrl(value: JsonElement): String? {
        val raw = ((value as? JsonObject)?.get("relativeUrl") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (!raw.startsWith("/") || raw.startsWith("//")) return null
        return raw
    }

    /** `AttachmentCreateUploadUrlResult`; null when the reply is missing either field. */
    fun uploadTarget(value: JsonElement): T3UploadTarget? {
        val attachmentId =
            ((value as? JsonObject)?.get("attachmentId") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val relative = relativeUrl(value) ?: return null
        if (attachmentId.isEmpty()) return null
        return T3UploadTarget(attachmentId = attachmentId, relativeUrl = relative)
    }
}

data class T3UploadTarget(
    val attachmentId: String,
    val relativeUrl: String,
)
