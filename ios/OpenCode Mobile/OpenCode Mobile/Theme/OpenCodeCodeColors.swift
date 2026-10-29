import Foundation

/// The one role-to-token table for highlighted code. Chat code fences, tool results, the File
/// Viewer, the Highlighting Lab, the Appearance preview, and the Component Gallery all color code
/// through it, so a role looks the same everywhere. Android keeps the same table.
///
/// Colored roles draw in their syntax token; variables, operators, punctuation, and plain text
/// draw in the plain code text color. A syntax preset taken from an editor theme also gives the
/// code surface (`syntax-background`, `syntax-foreground`); Match theme and presets without one
/// keep the UI theme's code background and text color.
nonisolated enum OpenCodeCodeColors {
    static let backgroundToken = "syntax-background"
    static let foregroundToken = "syntax-foreground"
    /// Plain code text when the syntax preset gives no foreground.
    static let fallbackTextToken = "text-strong"

    /// The token that colors `role`, or nil when the role draws in plain code text.
    static func token(for role: NativeCodeTokenRole) -> String? {
        switch role {
        case .keyword, .tag:
            "syntax-keyword"
        case .string:
            "syntax-string"
        case .number:
            "syntax-number"
        case .constant:
            "syntax-constant"
        case .comment:
            "syntax-comment"
        case .type:
            "syntax-type"
        case .function:
            "syntax-function"
        case .property:
            "syntax-property"
        case .attribute:
            "syntax-attribute"
        case .variable, .operatorToken, .punctuation, .plain:
            nil
        case .inserted:
            "diff-add"
        case .deleted:
            "diff-delete"
        case .warning:
            "syntax-diff-unknown"
        case .critical:
            "syntax-critical"
        }
    }

    /// Plain code text in `tokens`: the preset's foreground, else the UI theme's strong text.
    static func textHex(in tokens: [String: String]) -> String? {
        tokens[foregroundToken] ?? tokens[fallbackTextToken]
    }

    /// The preset's code background in `tokens`, or nil to keep the surface's own background.
    static func backgroundHex(in tokens: [String: String]) -> String? {
        tokens[backgroundToken]
    }

    /// The color of `role` in `tokens`: its token, else plain code text.
    static func hex(for role: NativeCodeTokenRole, in tokens: [String: String]) -> String? {
        if let token = token(for: role), let hex = tokens[token] {
            return hex
        }
        return textHex(in: tokens)
    }
}
