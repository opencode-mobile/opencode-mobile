package dev.opencode.mobile.network

import java.io.File
import java.io.OutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class JsonStreamWriter(
    private val output: OutputStream,
) {
    private val frames = ArrayDeque<Frame>().apply { add(Frame()) }

    private class Frame {
        var needsComma: Boolean = false
        var afterKey: Boolean = false
    }

    fun beginObject() {
        writeSeparator()
        writeRaw("{")
        frames.addLast(Frame())
    }

    fun endObject() {
        frames.removeLast()
        writeRaw("}")
        markValueWritten()
    }

    fun beginArray() {
        writeSeparator()
        writeRaw("[")
        frames.addLast(Frame())
    }

    fun endArray() {
        frames.removeLast()
        writeRaw("]")
        markValueWritten()
    }

    fun key(name: String) {
        writeSeparator()
        writeRaw(escape(name))
        writeRaw(":")
        frames.last().afterKey = true
        frames.last().needsComma = false
    }

    fun writeString(value: String) {
        writeSeparator()
        writeRaw(escape(value))
        markValueWritten()
    }

    fun writeInt(value: Int) {
        writeSeparator()
        writeRaw(value.toString())
        markValueWritten()
    }

    fun writeDataUrl(
        mime: String,
        file: File,
    ) {
        writeSeparator()
        writeRaw("\"data:")
        writeRaw(mime.lowercase())
        writeRaw(";base64,")
        writeBase64(file)
        writeRaw("\"")
        markValueWritten()
    }

    /** Writes an already-encoded JSON value, for small bodies built with kotlinx's JSON tree. */
    fun writeRawJson(encoded: String) {
        writeSeparator()
        writeRaw(encoded)
        markValueWritten()
    }

    fun writeDataUrlString(value: String) {
        writeString(value)
    }

    private fun writeSeparator() {
        val frame = frames.last()
        if (frame.afterKey) {
            frame.afterKey = false
            return
        }
        if (frame.needsComma) {
            writeRaw(",")
        }
    }

    private fun markValueWritten() {
        val frame = frames.last()
        frame.needsComma = true
        frame.afterKey = false
    }

    private fun writeRaw(value: String) {
        output.write(value.toByteArray(Charsets.UTF_8))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun writeBase64(file: File) {
        file.inputStream().use { input ->
            val buffer = ByteArray(24 * 1024)
            var leftover = ByteArray(0)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    if (leftover.isNotEmpty()) {
                        output.write(Base64.Default.encode(leftover).toByteArray(Charsets.UTF_8))
                    }
                    return
                }
                val combined = leftover + buffer.copyOf(read)
                val remainder = combined.size % 3
                leftover =
                    if (remainder == 0) {
                        ByteArray(0)
                    } else {
                        combined.copyOfRange(combined.size - remainder, combined.size)
                    }
                val complete =
                    if (remainder == 0) {
                        combined
                    } else {
                        combined.copyOf(combined.size - remainder)
                    }
                if (complete.isNotEmpty()) {
                    output.write(Base64.Default.encode(complete).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    companion object {
        fun escape(value: String): String {
            val output = StringBuilder(value.length + 2)
            output.append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> output.append("\\\"")
                    '\\' -> output.append("\\\\")
                    '\n' -> output.append("\\n")
                    '\r' -> output.append("\\r")
                    '\t' -> output.append("\\t")
                    else ->
                        if (ch.code < 0x20) {
                            output.append("\\u%04x".format(ch.code))
                        } else {
                            output.append(ch)
                        }
                }
            }
            output.append('"')
            return output.toString()
        }
    }
}
