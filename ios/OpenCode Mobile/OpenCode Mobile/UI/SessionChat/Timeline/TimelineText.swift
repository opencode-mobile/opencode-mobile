import Foundation

/// App text or server content in Session Chat timeline models.
///
/// The projector builds blocks off the main actor, and `OpenCodeText` is
/// main-actor isolated, so timeline models hold this nonisolated twin instead.
/// App text is a catalog key (`tool.title.shell`), looked up when it is shown.
/// Server and session content (tool names, paths, commands, agent and model
/// names, tool output, server errors) is `.verbatim` and never looked up.
nonisolated enum TimelineText: Hashable, Sendable {
    case localized(LocalizedStringResource)
    case verbatim(String)

    /// App text from the String Catalog. `key` is a structured key that matches
    /// Android (`tool.title.shell` here, `tool_title_shell` in `strings.xml`).
    static func app(_ key: StaticString, _ defaultValue: String.LocalizationValue) -> TimelineText {
        .localized(LocalizedStringResource(key, defaultValue: defaultValue))
    }

    /// Optional server content; `nil` stays `nil`.
    static func verbatim(ifPresent string: String?) -> TimelineText? {
        string.map(TimelineText.verbatim)
    }

    /// The display string in the current locale, for code that needs a `String`
    /// (components that take `String`, accessibility, nested arguments).
    var resolved: String {
        switch self {
        case .localized(let resource): String(localized: resource)
        case .verbatim(let string): string
        }
    }

    /// The content of server text; `nil` for app text.
    var verbatimValue: String? {
        if case .verbatim(let string) = self { return string }
        return nil
    }

    // LocalizedStringResource is Equatable but not Hashable; its key is enough
    // to spread hashes.
    func hash(into hasher: inout Hasher) {
        switch self {
        case .localized(let resource):
            hasher.combine(0)
            hasher.combine(resource.key)
        case .verbatim(let string):
            hasher.combine(1)
            hasher.combine(string)
        }
    }

    /// `a · b · c`, joined through the `timeline.list_separator` pattern; nil when empty.
    static func joined(_ parts: [TimelineText]) -> TimelineText? {
        guard var result = parts.first else { return nil }
        for next in parts.dropFirst() {
            let head = result.resolved
            let tail = next.resolved
            result = .app("timeline.list_separator", "\(head) · \(tail)")
        }
        return result
    }
}

extension TimelineText {
    /// Design-system text: localised for app text, verbatim for content.
    @MainActor var openCode: OpenCodeText {
        switch self {
        case .localized(let resource): OpenCodeText(resource)
        case .verbatim(let string): .verbatim(string)
        }
    }
}
