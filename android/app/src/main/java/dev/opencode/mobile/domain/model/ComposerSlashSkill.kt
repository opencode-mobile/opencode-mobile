package dev.opencode.mobile.domain.model

/**
 * A skill the composer can offer from `/`. Selecting one inserts a `$name`
 * mention; the T3 server dispatches that mention from any position in the prompt.
 */
data class ComposerSlashSkill(
    val name: String,
    val description: String? = null,
    val displayName: String? = null,
    val shortDescription: String? = null,
) {
    val insertion: String get() = "$$name "
    val menuTitle: String get() = "skill:$name"
    val menuSubtitle: String? get() = shortDescription ?: description

    /** `/skill` and `/skill:` list every skill. Any other query matches the name, display name, or description. */
    fun matches(query: String): Boolean {
        val normalized = query.trim().lowercase()
        val skillQuery =
            when {
                normalized == "skill" -> ""
                normalized.startsWith("skill:") -> normalized.removePrefix("skill:")
                else -> normalized
            }
        if (skillQuery.isEmpty()) return true
        return listOfNotNull(name, displayName, shortDescription, description).any {
            it.lowercase().contains(skillQuery)
        }
    }
}
