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

    /**
     * A moment shown as [style] in the reader's locale and time zone. Formatted when resolved,
     * like the other cases, so a language change does not leave an English date in UI state.
     */
    data class DateTime(
        val epochMs: Long,
        val style: DateTimeStyle,
    ) : UiText

    /**
     * A number written in the reader's locale when resolved ("1.5" in en-US, "1,5" in de-DE),
     * with [minFractionDigits] to [maxFractionDigits] decimals, rounded half up, grouped.
     * [suffix] is a unit symbol written right after it, as is, for units that are not keyed yet
     * (the TUI-style "6.4s").
     */
    data class Number(
        val value: Double,
        val minFractionDigits: Int = 0,
        val maxFractionDigits: Int = 0,
        val suffix: String = "",
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

        fun dateTime(
            epochMs: Long,
            style: DateTimeStyle,
        ): UiText = DateTime(epochMs, style)

        fun number(
            value: Double,
            minFractionDigits: Int = 0,
            maxFractionDigits: Int = 0,
        ): UiText = Number(value, minFractionDigits, maxFractionDigits)
    }
}

/** How a [UiText.DateTime] is written. Each style follows the locale's own pattern. */
enum class DateTimeStyle {
    /** Month and day without the year: "Nov 27" in en-US, "27. Nov." in de-DE. */
    MonthDay,

    /** Weekday, month and day: "Friday, Nov 27" in en-US, "Freitag, 27. Nov." in de-DE. */
    WeekdayMonthDay,
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

    /** [epochMs] written as [style] in this lookup's locale and time zone. */
    fun dateTime(
        epochMs: Long,
        style: DateTimeStyle,
    ): String

    /** [number] written in this lookup's locale. */
    fun number(number: UiText.Number): String
}

fun UiText.resolve(lookup: UiTextLookup): String =
    when (this) {
        is UiText.Verbatim -> value
        is UiText.Resource -> lookup.string(id, resolveArgs(args, lookup))
        is UiText.Plural -> lookup.plural(id, count, resolveArgs(args, lookup))
        is UiText.DateTime -> lookup.dateTime(epochMs, style)
        is UiText.Number -> lookup.number(this)
    }

private fun resolveArgs(
    args: List<Any>,
    lookup: UiTextLookup,
): Array<Any> = args.map { if (it is UiText) it.resolve(lookup) else it }.toTypedArray()
