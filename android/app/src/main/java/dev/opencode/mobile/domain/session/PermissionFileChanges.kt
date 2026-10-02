package dev.opencode.mobile.domain.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Edit/write permission metadata as a compact file list. v2 sends
 * `metadata.files[] {file, additions, deletions, patch}`; v1 sends one
 * `filepath` (or `filePath`) plus a unified `diff`.
 */
object PermissionFileChanges {
    fun from(metadata: JsonObject): List<PermissionFileChange> {
        val files = metadata["files"] as? JsonArray
        if (files != null) {
            return files.mapNotNull { element ->
                val file = element as? JsonObject ?: return@mapNotNull null
                val path = file.string("file") ?: file.string("path") ?: return@mapNotNull null
                val counted = file.string("patch")?.let(::count)
                PermissionFileChange(
                    path = path,
                    additions = file.int("additions") ?: counted?.first ?: 0,
                    deletions = file.int("deletions") ?: counted?.second ?: 0,
                )
            }
        }
        val path = metadata.string("filepath") ?: metadata.string("filePath") ?: return emptyList()
        val (additions, deletions) = metadata.string("diff")?.let(::count) ?: (0 to 0)
        return listOf(PermissionFileChange(path = path, additions = additions, deletions = deletions))
    }

    /** `+`/`-` body lines of a unified diff, ignoring the `+++`/`---` headers. */
    internal fun count(patch: String): Pair<Int, Int> {
        var additions = 0
        var deletions = 0
        patch.lineSequence().forEach { line ->
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> additions += 1
                line.startsWith("-") -> deletions += 1
            }
        }
        return additions to deletions
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
