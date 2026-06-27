package dev.opencode.mobile.markdown

internal class Utf8IndexMap(
    input: String,
) {
    val bytes: ByteArray = input.encodeToByteArray()
    private val charToByte = IntArray(input.length + 1)
    private val byteToChar = IntArray(bytes.size + 1)

    init {
        var charIndex = 0
        var byteIndex = 0

        while (charIndex < input.length) {
            val codePoint = input.codePointAt(charIndex)
            val charCount = input.charCount(codePoint)
            val byteCount = utf8ByteCount(codePoint)
            val nextCharIndex = charIndex + charCount
            val nextByteIndex = byteIndex + byteCount

            for (index in charIndex until nextCharIndex) {
                charToByte[index] = byteIndex
            }
            for (index in byteIndex until nextByteIndex) {
                byteToChar[index] = charIndex
            }
            byteToChar[nextByteIndex] = nextCharIndex

            charIndex = nextCharIndex
            byteIndex = nextByteIndex
        }

        charToByte[input.length] = bytes.size
        byteToChar[bytes.size] = input.length
    }

    fun byteIndexFor(charIndex: Int): Int = charToByte[charIndex]

    fun charIndexFor(byteIndex: Int): Int = byteToChar[byteIndex.coerceIn(byteToChar.indices)]
}

private fun String.codePointAt(index: Int): Int {
    val high = this[index].code
    if (high in 0xD800..0xDBFF && index + 1 < length) {
        val low = this[index + 1].code
        if (low in 0xDC00..0xDFFF) {
            return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
        }
    }
    return high
}

private fun String.charCount(codePoint: Int): Int = if (codePoint > 0xFFFF) 2 else 1

private fun utf8ByteCount(codePoint: Int): Int =
    when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }
