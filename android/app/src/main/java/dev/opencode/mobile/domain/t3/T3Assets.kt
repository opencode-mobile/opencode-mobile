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
    const val PREVIEW_MIME = "image/jpeg"
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

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

    fun relativeUrl(value: JsonElement): String? {
        val raw = ((value as? JsonObject)?.get("relativeUrl") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (!raw.startsWith("/") || raw.startsWith("//")) return null
        return raw
    }
}
