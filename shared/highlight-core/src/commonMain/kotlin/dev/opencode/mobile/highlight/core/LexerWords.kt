package dev.opencode.mobile.highlight.core

/**
 * A word table for lexers: maps an ASCII word to an int kind with no allocation per lookup.
 * Words are bucketed by their first character; [caseInsensitive] tables match ASCII letters in
 * any case (SQL, Dockerfile instructions). Fill it once in the owning lexer's initializer.
 */
internal class LexerWords(
    private val caseInsensitive: Boolean = false,
) {
    private val words = arrayOfNulls<Array<String>>(128)
    private val kinds = arrayOfNulls<IntArray>(128)

    fun register(
        kind: Int,
        vararg names: String,
    ) {
        for (name in names) {
            val key = if (caseInsensitive) name.lowercase() else name
            val first = key[0].code
            words[first] = (words[first] ?: emptyArray()) + key
            kinds[first] = (kinds[first] ?: IntArray(0)) + kind
        }
    }

    /** The kind of `text[from, to)`, or 0 when the word is not in the table. */
    fun lookup(
        text: CharArray,
        from: Int,
        to: Int,
    ): Int {
        if (to <= from) return 0
        val first = fold(text[from]).code
        if (first >= 128) return 0
        val bucket = words[first] ?: return 0
        val length = to - from
        for (slot in bucket.indices) {
            val word = bucket[slot]
            if (word.length != length) continue
            var matches = true
            for (offset in 1 until length) {
                if (fold(text[from + offset]) != word[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return kinds[first]!![slot]
        }
        return 0
    }

    private fun fold(char: Char): Char = if (caseInsensitive && char in 'A'..'Z') char + 32 else char
}

/**
 * Hashes for delimiters a lexer must remember across lines (shell and Ruby heredoc words, SQL
 * dollar-quote tags) when the state has no room for the characters themselves. The hash covers
 * the length and every character, folded to [bits] bits; a different line of the same hash
 * matches too, which at 18 bits or more is rare enough for highlighting.
 */
internal object LexerDelimiter {
    /** Starts a hash; feed characters with [add] and finish with [finish]. */
    const val SEED: Int = -0x7ee3623b

    fun add(
        hash: Int,
        char: Char,
    ): Int = (hash xor char.code) * 0x01000193

    fun finish(
        hash: Int,
        length: Int,
        bits: Int,
    ): Int {
        var mixed = add(hash, (length and 0xFFFF).toChar())
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x2c1b3c6d
        mixed = mixed xor (mixed ushr 12)
        return mixed and ((1 shl bits) - 1)
    }

    /** The hash of `text[from, to)`. */
    fun of(
        text: CharArray,
        from: Int,
        to: Int,
        bits: Int,
    ): Int {
        var hash = SEED
        for (index in from until to) hash = add(hash, text[index])
        return finish(hash, to - from, bits)
    }
}
