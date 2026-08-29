import Foundation

protocol TerminalSocketClient: AnyObject {
    func open(url: String, listener: TerminalSocketListener)
    func sendText(_ text: String)
    func close()
}

protocol TerminalSocketListener: AnyObject {
    func didOpen()
    func didReceiveText(_ text: String)
    func didReceiveBinary(_ data: Data)
    func didClose(code: Int)
}
