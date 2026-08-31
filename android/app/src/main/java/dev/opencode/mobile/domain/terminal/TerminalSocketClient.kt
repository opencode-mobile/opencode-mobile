package dev.opencode.mobile.domain.terminal

interface TerminalSocketClient {
    fun open(
        url: String,
        listener: TerminalSocketListener,
    )

    fun sendText(text: String)

    fun close()
}

interface TerminalSocketListener {
    fun onOpen()

    fun onText(text: String)

    fun onBinary(bytes: ByteArray)

    fun onClosed(code: Int)
}
