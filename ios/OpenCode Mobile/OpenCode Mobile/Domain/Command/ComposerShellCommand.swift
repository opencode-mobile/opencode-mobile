import Foundation

/// Shell mode in the composer, like `!` in the TUI and web app: a draft whose first character is
/// `!` runs the rest as a shell command in the session instead of sending a prompt.
///
/// The `!` must be the very first character. A draft with leading whitespace (` !ls`) or a `!`
/// later in the text stays a prompt.
nonisolated enum ComposerShellCommand {
    static let prefix: Character = "!"

    static func isShellMode(_ text: String) -> Bool {
        text.first == prefix
    }

    /// The command to run, trimmed, with inner newlines kept so a multi-line draft runs as one
    /// script. Nil when `text` is not in shell mode or has nothing after the `!`.
    static func command(_ text: String) -> String? {
        guard isShellMode(text) else { return nil }
        let command = text.dropFirst().trimmingCharacters(in: .whitespacesAndNewlines)
        return command.isEmpty ? nil : command
    }

    /// `text` with the shell prefix removed, used to leave shell mode and keep the rest of the draft.
    static func exitShellMode(_ text: String) -> String {
        isShellMode(text) ? String(text.dropFirst()) : text
    }
}
