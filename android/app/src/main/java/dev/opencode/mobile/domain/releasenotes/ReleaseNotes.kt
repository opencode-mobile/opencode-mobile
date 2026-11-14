package dev.opencode.mobile.domain.releasenotes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** The app a release-notes entry is read for. [key] is the platform key in `release-notes.json`. */
enum class ReleaseNotesPlatform(
    val key: String,
) {
    Android("android"),
    Ios("ios"),
}

enum class ReleaseNoteKind {
    New,
    Improved,
    Fixed,
}

/** One change in a release. [iconKey] is a semantic icon name such as `edit` or `fix`. */
data class ReleaseNoteItem(
    val kind: ReleaseNoteKind,
    val iconKey: String,
    val title: String,
    val body: String,
)

/** One shipped release of this app, with only the items that apply to it. */
data class ReleaseNote(
    val version: SemanticVersion,
    /** Store release date, or null before the release workflow recorded it. */
    val released: LocalDate?,
    val items: List<ReleaseNoteItem>,
) {
    /** New items first, then improvements: everything shown above the Fixes header. */
    val highlights: List<ReleaseNoteItem>
        get() =
            items.filter { it.kind == ReleaseNoteKind.New } +
                items.filter { it.kind == ReleaseNoteKind.Improved }

    val fixes: List<ReleaseNoteItem>
        get() = items.filter { it.kind == ReleaseNoteKind.Fixed }
}

/** MAJOR.MINOR.PATCH, compared numerically. */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads `1.5.0`, also with a prerelease or build suffix (`1.5.0-internal.1`,
         * `1.5.0+42`), which is dropped. Anything else is null.
         */
        fun parse(text: String?): SemanticVersion? {
            val core =
                text
                    ?.trim()
                    ?.substringBefore('+')
                    ?.substringBefore('-')
                    ?: return null
            val parts = core.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part -> part.takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: return null }
            return SemanticVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

/** Reads `release-notes.json` into [ReleaseNote]s for one platform. */
object ReleaseNotesParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Releases that carry [platform]'s key, newest first, each with the items that apply to
     * [platform]. Releases left with no items, and releases without a readable version, are
     * dropped. An unreadable file reads as no releases.
     */
    fun parse(
        text: String,
        platform: ReleaseNotesPlatform,
    ): List<ReleaseNote> {
        val file = runCatching { json.decodeFromString<ReleaseNotesFileDto>(text) }.getOrNull() ?: return emptyList()
        return file.releases
            .mapNotNull { release -> release.toReleaseNote(platform) }
            .sortedByDescending { it.version }
    }

    private fun ReleaseDto.toReleaseNote(platform: ReleaseNotesPlatform): ReleaseNote? {
        val target =
            when (platform) {
                ReleaseNotesPlatform.Android -> android
                ReleaseNotesPlatform.Ios -> ios
            } ?: return null
        val version = SemanticVersion.parse(target.version) ?: return null
        val items =
            items
                .filter { item -> item.platforms == null || platform.key in item.platforms }
                .filter { item -> item.title.isNotBlank() }
                .map { item ->
                    ReleaseNoteItem(
                        kind = item.kind.toKind(),
                        iconKey = item.icon,
                        title = item.title,
                        body = item.body,
                    )
                }
        if (items.isEmpty()) return null
        return ReleaseNote(
            version = version,
            released = target.released?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            items = items,
        )
    }

    /** An unknown kind reads as an improvement. */
    private fun String.toKind(): ReleaseNoteKind =
        when (this) {
            "new" -> ReleaseNoteKind.New
            "fixed" -> ReleaseNoteKind.Fixed
            else -> ReleaseNoteKind.Improved
        }
}

/** Releases up to and including [installed], newest first. Debug builds can be ahead of the notes. */
fun List<ReleaseNote>.upTo(installed: SemanticVersion): List<ReleaseNote> = filter { it.version <= installed }

@Serializable
private data class ReleaseNotesFileDto(
    val releases: List<ReleaseDto> = emptyList(),
)

@Serializable
private data class ReleaseDto(
    val android: PlatformReleaseDto? = null,
    val ios: PlatformReleaseDto? = null,
    val items: List<ReleaseItemDto> = emptyList(),
)

@Serializable
private data class PlatformReleaseDto(
    val version: String = "",
    val released: String? = null,
)

@Serializable
private data class ReleaseItemDto(
    val kind: String = "",
    val icon: String = "",
    val title: String = "",
    val body: String = "",
    val platforms: List<String>? = null,
)
