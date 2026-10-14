package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.model.ComposerSlashSkill

/** Skills a `/` menu may offer: enabled, user-invocable, and one row per name. */
object T3SlashSkills {
    fun visible(skills: List<T3ProviderSkill>): List<ComposerSlashSkill> {
        val seen = HashSet<String>()
        val items = ArrayList<ComposerSlashSkill>()
        for (skill in skills) {
            if (!skill.enabled || !skill.userInvocable) continue
            val key = skill.name.trim().lowercase()
            if (key.isEmpty() || !seen.add(key)) continue
            items +=
                ComposerSlashSkill(
                    name = skill.name.trim(),
                    description = skill.description,
                    displayName = skill.displayName,
                    shortDescription = skill.shortDescription,
                )
        }
        return items
    }
}
