package dev.opencode.mobile.domain.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text that a Screen Model puts in UI state without resolving it.
 *
 * ViewModels never call `getString`: they hold a [UiText] and the view resolves it with
 * `resolve()` from `ui.text`, so a language change re-renders in the new language instead of
 * keeping stale strings. Server and session content (session titles, file names, model and provider names,
 * server errors, transcripts) uses [Verbatim] and is never looked up as a resource.
 *
 * Arguments are positional (`%1$s`, `%2$d`) and may themselves be [UiText]; nested values are
 * resolved first.
 */
sealed interface UiText {
    /** A `<string>` resource with positional arguments. */
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** A `<plurals>` resource. [count] picks the quantity; [args] fill the placeholders. */
    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    /** Server or session content, shown as is. */
    data class Verbatim(
        val value: String,
    ) : UiText

    companion object {
        fun resource(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Resource(id, args.toList())

        /** Plural whose only placeholder is the count, such as `%1$d Connection Profiles`. */
        fun plural(
            @PluralsRes id: Int,
            count: Int,
        ): UiText = Plural(id, count, listOf(count))

        fun plural(
            @PluralsRes id: Int,
            count: Int,
            vararg args: Any,
        ): UiText = Plural(id, count, args.toList())

        fun verbatim(value: String): UiText = Verbatim(value)
    }
}

/** Where resolved text comes from. Android resources in the app; a fake in unit tests. */
interface UiTextLookup {
    fun string(
        @StringRes id: Int,
        args: Array<out Any>,
    ): String

    fun plural(
        @PluralsRes id: Int,
        count: Int,
        args: Array<out Any>,
    ): String
}

fun UiText.resolve(lookup: UiTextLookup): String =
    when (this) {
        is UiText.Verbatim -> value
        is UiText.Resource -> lookup.string(id, resolveArgs(args, lookup))
        is UiText.Plural -> lookup.plural(id, count, resolveArgs(args, lookup))
    }

private fun resolveArgs(
    args: List<Any>,
    lookup: UiTextLookup,
): Array<Any> = args.map { if (it is UiText) it.resolve(lookup) else it }.toTypedArray()
