package dev.opencode.mobile.ui.text

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.opencode.mobile.domain.text.DateTimeStyle
import dev.opencode.mobile.domain.text.LocalizedNumberFormat
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.domain.time.LocalizedDateTimeFormat
import java.time.ZoneId
import java.util.Locale

/** [UiTextLookup] backed by Android [Resources]; dates follow the same configuration's locale. */
class ResourcesUiTextLookup(
    private val resources: Resources,
) : UiTextLookup {
    private val dates: LocalizedDateTimeFormat by lazy {
        platformDateTimeFormat(resources.configuration.locales[0])
    }

    override fun string(
        id: Int,
        args: Array<out Any>,
    ): String = if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)

    override fun plural(
        id: Int,
        count: Int,
        args: Array<out Any>,
    ): String = resources.getQuantityString(id, count, *args)

    override fun dateTime(
        epochMs: Long,
        style: DateTimeStyle,
    ): String = dates.format(epochMs, style)

    override fun number(number: UiText.Number): String = LocalizedNumberFormat.format(number, resources.configuration.locales[0])
}

/**
 * [LocalizedDateTimeFormat] with the platform's CLDR patterns for [locale], in the device time
 * zone. [is24Hour] is the reader's clock setting (`DateFormat.is24HourFormat`), for times.
 */
fun platformDateTimeFormat(
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
    is24Hour: Boolean = false,
): LocalizedDateTimeFormat =
    LocalizedDateTimeFormat(
        locale = locale,
        zone = zone,
        monthDayPattern = DateFormat.getBestDateTimePattern(locale, "MMMd"),
        weekdayMonthDayPattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMd"),
        timePattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"),
    )

/** Resolves in the current configuration; recomposes when the locale changes, like stringResource. */
@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = resolve(ResourcesUiTextLookup(LocalResources.current))
