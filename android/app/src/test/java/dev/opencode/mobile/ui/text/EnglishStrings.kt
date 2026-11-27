package dev.opencode.mobile.ui.text

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.DateTimeStyle
import dev.opencode.mobile.domain.text.LocalizedNumberFormat
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.domain.time.LocalizedDateTimeFormat
import org.w3c.dom.Element
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves [UiText] against the English `res/values/strings.xml` the way Android does for a
 * configuration in [locale]: format arguments, numbers and dates follow [locale], the words stay
 * English. JVM unit tests use it to assert on the text users read without Robolectric, and
 * without depending on the host's locale or time zone.
 */
open class StringsXmlLookup(
    private val locale: Locale,
    private val dates: LocalizedDateTimeFormat,
) : UiTextLookup {
    override fun string(
        id: Int,
        args: Array<out Any>,
    ): String {
        val format = StringsXml.strings.getValue(StringsXml.stringNames.getValue(id))
        return if (args.isEmpty()) format else String.format(locale, format, *args)
    }

    override fun plural(
        id: Int,
        count: Int,
        args: Array<out Any>,
    ): String {
        val items = StringsXml.plurals.getValue(StringsXml.pluralNames.getValue(id))
        val format = if (count == 1) items.getValue("one") else items.getValue("other")
        return String.format(locale, format, *args)
    }

    override fun dateTime(
        epochMs: Long,
        style: DateTimeStyle,
    ): String = dates.format(epochMs, style)

    override fun number(number: UiText.Number): String = LocalizedNumberFormat.format(number, locale)
}

/** en-US in UTC, with the patterns Android's CLDR data gives en-US. */
object EnglishStrings : StringsXmlLookup(Locale.US, LocalizedDateTimeFormat(Locale.US, ZoneOffset.UTC))

/**
 * The English words with German (de-DE) numbers and dates in UTC, as Android shows a string that
 * has no German translation yet: for tests that the locale reaches the formatting.
 */
object GermanFormatsStrings : StringsXmlLookup(
    Locale.GERMANY,
    LocalizedDateTimeFormat(
        Locale.GERMANY,
        ZoneOffset.UTC,
        // Android's CLDR patterns for de-DE.
        monthDayPattern = "d. MMM",
        weekdayMonthDayPattern = "EEEE, d. MMM",
        timePattern = "HH:mm",
    ),
)

private object StringsXml {
    val stringNames: Map<Int, String> by lazy { resourceNames(R.string::class.java) }
    val pluralNames: Map<Int, String> by lazy { resourceNames(R.plurals::class.java) }

    val strings = mutableMapOf<String, String>()
    val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val file =
            listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
                .map(::File)
                .first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.documentElement
        val stringNodes = root.getElementsByTagName("string")
        for (index in 0 until stringNodes.length) {
            val element = stringNodes.item(index) as Element
            strings[element.getAttribute("name")] = unescape(element.textContent)
        }
        val pluralNodes = root.getElementsByTagName("plurals")
        for (index in 0 until pluralNodes.length) {
            val element = pluralNodes.item(index) as Element
            val items = element.getElementsByTagName("item")
            plurals[element.getAttribute("name")] =
                (0 until items.length).associate { itemIndex ->
                    val item = items.item(itemIndex) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
        }
    }

    private fun resourceNames(type: Class<*>): Map<Int, String> = type.fields.associate { field -> field.getInt(null) to field.name }

    private fun unescape(raw: String): String =
        raw
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}

/** The English text users read. */
fun UiText.english(): String = resolve(EnglishStrings)
