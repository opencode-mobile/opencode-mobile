import SwiftUI

/// Text that a design-system component shows.
///
/// App UI text is localised: a string literal, an interpolated literal, or a
/// `LocalizedStringResource` is looked up in `Localizable.xcstrings` when it is
/// displayed. New app text uses a structured key that matches Android
/// (`servers.title` here, `servers_title` in `strings.xml`).
///
/// Server and session content (session titles, file names, model and provider
/// names, server errors, transcripts) must use `.verbatim(_:)`, which is shown
/// as is and never looked up as a key. Developer-only surfaces (the component
/// gallery, playground, highlighting lab) are not translated and use it too.
///
/// ```swift
/// OpenCodeTopBar(title: "servers.title")                     // localised key
/// OpenCodeListItem(title: .verbatim(profile.displayName))     // server content
/// OpenCodeText(LocalizedStringResource(
///     "servers.remove_dialog.title_named",
///     defaultValue: "Remove “\(name)”?"                       // name stays an argument
/// ))
/// ```
///
/// Passing a plain `String` does not compile on purpose: decide whether it is
/// app text (move it to the catalog), content (`.verbatim`), or app text not yet
/// extracted (`.unlocalized`).
struct OpenCodeText: Hashable, Sendable, ExpressibleByStringInterpolation {
    enum Storage: Hashable, Sendable {
        case localized(LocalizedStringResource)
        case verbatim(String)

        // LocalizedStringResource is Equatable but not Hashable; its key is enough
        // to spread hashes.
        func hash(into hasher: inout Hasher) {
            switch self {
            case let .localized(resource):
                hasher.combine(0)
                hasher.combine(resource.key)
            case let .verbatim(string):
                hasher.combine(1)
                hasher.combine(string)
            }
        }
    }

    let storage: Storage

    init(_ resource: LocalizedStringResource) {
        storage = .localized(resource)
    }

    init(verbatim string: String) {
        storage = .verbatim(string)
    }

    /// Server or session content, shown as is.
    static func verbatim(_ string: String) -> OpenCodeText {
        OpenCodeText(verbatim: string)
    }

    static let empty = OpenCodeText(verbatim: "")

    /// Optional server or session content, shown as is; `nil` stays `nil`.
    static func verbatim(ifPresent string: String?) -> OpenCodeText? {
        string.map(OpenCodeText.init(verbatim:))
    }

    /// Server content (such as an error message) shown verbatim when it is not
    /// blank, otherwise the localised `fallback`.
    static func verbatim(_ content: String?, fallback: OpenCodeText) -> OpenCodeText {
        guard let content, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return fallback
        }
        return .verbatim(content)
    }

    /// App text that is still built as a `String` in code and not yet in the
    /// String Catalog. Shown as is, like `.verbatim`, but marks text to extract,
    /// so the hardcoded-text check counts it. Not for server or session content.
    static func unlocalized(_ string: String) -> OpenCodeText {
        OpenCodeText(verbatim: string)
    }

    /// Optional form of `unlocalized(_:)`; `nil` stays `nil`.
    static func unlocalized(ifPresent string: String?) -> OpenCodeText? {
        string.map(OpenCodeText.init(verbatim:))
    }

    init(stringLiteral value: String) {
        storage = .localized(LocalizedStringResource(stringLiteral: value))
    }

    init(stringInterpolation: String.LocalizationValue.StringInterpolation) {
        storage = .localized(LocalizedStringResource(stringInterpolation: stringInterpolation))
    }

    /// The display string in the current locale, for code that needs a `String`
    /// (measuring, accessibility values, emptiness checks).
    var resolved: String {
        switch storage {
        case let .localized(resource): String(localized: resource)
        case let .verbatim(string): string
        }
    }

    var isEmpty: Bool { resolved.isEmpty }
}

// The helpers below are labelled on purpose. An unlabelled `Text(_: OpenCodeText)`
// would make every `Text("literal")` ambiguous with SwiftUI's `LocalizedStringKey`
// overload.

extension Text {
    /// Localised for app text, verbatim for content.
    init(openCode text: OpenCodeText) {
        switch text.storage {
        case let .localized(resource): self.init(resource)
        case let .verbatim(string): self.init(verbatim: string)
        }
    }
}

extension View {
    /// Accessibility label from design-system text.
    func accessibilityLabel(openCode text: OpenCodeText) -> ModifiedContent<Self, AccessibilityAttachmentModifier> {
        accessibilityLabel(Text(openCode: text))
    }
}
