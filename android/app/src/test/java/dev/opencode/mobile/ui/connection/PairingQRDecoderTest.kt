package dev.opencode.mobile.ui.connection

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQRDecoderTest {
    private val decoder = PairingQRDecoder()

    @Test
    fun decode_readsPairingPayload() {
        val payload =
            """{"urls":["http://192.168.1.60:4096"],"username":"opencode","password":"secret"}"""
        val frame = qrLuminance(payload)

        assertEquals(
            payload,
            decoder.decode(frame.bytes, frame.width, frame.height, frame.width, pixelStride = 1),
        )
        assertEquals(
            payload,
            decoder.decode(frame.bytes, frame.width, frame.height, frame.width, pixelStride = 1),
        )
    }

    @Test
    fun decode_ignoresRowPadding() {
        val payload = "http://192.168.1.60:4096/auth/connect/abc_DEF-123"
        val frame = qrLuminance(payload)
        val padding = 12
        val rowStride = frame.width + padding
        val padded = ByteArray(rowStride * frame.height)
        for (y in 0 until frame.height) {
            frame.bytes.copyInto(
                padded,
                destinationOffset = y * rowStride,
                startIndex = y * frame.width,
                endIndex = (y + 1) * frame.width,
            )
            for (x in frame.width until rowStride) {
                padded[y * rowStride + x] = 0x20
            }
        }

        assertEquals(
            payload,
            decoder.decode(padded, frame.width, frame.height, rowStride, pixelStride = 1),
        )
    }

    @Test
    fun decode_readsStridedPixels() {
        val payload = "opencode"
        val frame = qrLuminance(payload)
        val pixelStride = 2
        val rowStride = frame.width * pixelStride
        val strided = ByteArray(rowStride * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                strided[y * rowStride + x * pixelStride] = frame.bytes[y * frame.width + x]
            }
        }

        assertEquals(
            payload,
            decoder.decode(strided, frame.width, frame.height, rowStride, pixelStride),
        )
    }

    @Test
    fun decode_readsAnInvertedCodeFromALightTerminal() {
        // `opencode pair` draws light modules as blocks, so a light terminal shows the code inverted.
        val payload = """{"code":"Ab3dEf9hKz","urls":["https://example-route.opentunnel.xyz"]}"""
        val frame = lightTerminalLuminance(payload)

        assertEquals(
            payload,
            decoder.decode(frame.bytes, frame.width, frame.height, frame.width, pixelStride = 1),
        )
    }

    @Test
    fun decode_returnsNullWhenTheFrameHasNoCode() {
        val bytes = ByteArray(96 * 96) { 0xFF.toByte() }

        assertNull(decoder.decode(bytes, 96, 96, rowStride = 96, pixelStride = 1))
    }

    @Test
    fun decode_returnsNullForAShortBuffer() {
        assertNull(decoder.decode(ByteArray(10), width = 32, height = 32, rowStride = 32, pixelStride = 1))
    }

    private fun qrLuminance(text: String): Frame {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320)
        val width = matrix.width
        val height = matrix.height
        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                bytes[row + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        return Frame(bytes, width, height)
    }

    /** Inverted code on a light background, as a light-theme terminal renders it. */
    private fun lightTerminalLuminance(text: String): Frame {
        val code = qrLuminance(text)
        val margin = 40
        val width = code.width + 2 * margin
        val height = code.height + 2 * margin
        val bytes = ByteArray(width * height) { 0xFA.toByte() }
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                val dark = code.bytes[y * code.width + x] == 0.toByte()
                bytes[(y + margin) * width + x + margin] = if (dark) 0xFA.toByte() else 0x1E
            }
        }
        return Frame(bytes, width, height)
    }

    private class Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )
}
