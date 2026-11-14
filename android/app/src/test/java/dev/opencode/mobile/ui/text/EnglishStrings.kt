package dev.opencode.mobile.ui.text

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.domain.text.resolve
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves [UiText] against the English `res/values/strings.xml`, so JVM unit tests can
 * assert on the text users read without Robolectric.
 */
object EnglishStrings : UiTextLookup {
    private val stringNames: Map<Int, String> by lazy { resourceNames(R.string::class.java) }
    private val pluralNames: Map<Int, String> by lazy { resourceNames(R.plurals::class.java) }

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

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

    override fun string(
        id: Int,
        args: Array<out Any>,
    ): String {
        val format = strings.getValue(stringNames.getValue(id))
        return if (args.isEmpty()) format else String.format(format, *args)
    }

    override fun plural(
        id: Int,
        count: Int,
        args: Array<out Any>,
    ): String {
        val items = plurals.getValue(pluralNames.getValue(id))
        val format = if (count == 1) items.getValue("one") else items.getValue("other")
        return String.format(format, *args)
    }

    private fun resourceNames(type: Class<*>): Map<Int, String> =
        type.fields.associate { field -> field.getInt(null) to field.name }

    private fun unescape(raw: String): String =
        raw
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}

/** The English text users read. */
fun UiText.english(): String = resolve(EnglishStrings)
