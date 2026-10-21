package dev.opencode.mobile.domain.worktree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * V1 `GET /experimental/worktree` bodies (the project's sandboxes, without the main checkout).
 * A recognized shape is an array, or an object whose `data` is an array, of strings or
 * `{ "directory": "..." }`. Null means the body is not that shape.
 */
object WorktreeDirectoryParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<String>? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val array =
            when (element) {
                is JsonArray -> element
                is JsonObject -> element["data"] as? JsonArray ?: return null
                else -> return null
            }
        val directories = mutableListOf<String>()
        for (item in array) {
            when (item) {
                is JsonPrimitive -> {
                    if (!item.isString) return null
                    val value = item.content.trim()
                    if (value.isNotEmpty()) directories += value
                }
                is JsonObject -> {
                    when (val directory = item["directory"]) {
                        null, is JsonNull -> Unit
                        is JsonPrimitive -> {
                            if (!directory.isString) return null
                            val value = directory.contentOrNull?.trim().orEmpty()
                            if (value.isNotEmpty()) directories += value
                        }
                        else -> return null
                    }
                }
                else -> return null
            }
        }
        return directories
    }
}
