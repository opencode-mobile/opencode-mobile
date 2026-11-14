package dev.opencode.hexcode.session

import java.io.File
import java.util.Base64

/**
 * An image that goes with a message (app-plan.md, Attach › Sending): the prepared JPEG on the phone and its [name] in
 * the request ("Photo 1.jpg", "Screenshot 1.jpg").
 */
data class PromptImage(
    val name: String,
    val file: File,
) {
    /**
     * `data:image/jpeg;base64,…` with canonical base64: no line breaks, which the server refuses (attach-notes.md, §1).
     * The basic `java.util.Base64` encoder never wraps, the same as Android's `Base64.NO_WRAP`.
     */
    fun dataUrl(): String = PREFIX + Base64.getEncoder().encodeToString(file.readBytes())

    companion object {
        const val PREFIX = "data:image/jpeg;base64,"
    }
}
